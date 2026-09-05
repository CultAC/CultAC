package ac.cult.cultac.utils.blockplace;

import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.GameMasterBlockItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Optional;

// Netty-thread-only placement simulator. This path must never read live world state.
public final class NmsBlockPlaceResolver {
    private static final int PLACEMENT_FLAGS = Block.UPDATE_ALL_IMMEDIATE;
    private static final int MAX_UPDATE_DEPTH = 512;
    // Shields were hard-coded by AxeItem through 1.21.3. Newer clients moved the same
    // decision to the blocks_attacks component, which is absent from the old runtime ABI.
    private static final DataComponentType<?> BLOCKS_ATTACKS_COMPONENT = findDataComponent("BLOCKS_ATTACKS");
    private NmsBlockPlaceResolver() {
    }

    public static boolean applyBlockPlace(CultPlayer player, BlockPlace place) {
        // Live world access is forbidden here. Prediction must resolve only from the player's compensated state.
        PlacementSnapshot snapshot = PlacementSnapshot.capture(player, place);
        if (snapshot.getItemStack().isEmpty() || !(snapshot.getItemStack().getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        if (blockItem instanceof GameMasterBlockItem && !player.canUseGameMasterBlocks()) {
            return false;
        }

        PlacementResult result = simulatePlace(PlacementBlockAccess.fromCompensatedWorld(player.compensatedWorld), snapshot, blockItem);
        if (!result.isSuccess()) {
            if (player.debugPlaces && result.getResyncReason() != null) {
                player.sendMessage("Resolved place failed: " + result.getResyncReason());
            }
            return false;
        }

        if (player.debugPlaces) {
            logResolvedChangeSet(player, result.getChangedBlocks());
        }

        return applyResolvedStates(place, result);
    }

    public static boolean applyIgnitionItem(CultPlayer player, BlockPlace place, boolean consumeInventory) {
        PlacementSnapshot snapshot = PlacementSnapshot.capture(player, place);
        PlacementResult result = simulateIgnition(PlacementBlockAccess.fromCompensatedWorld(player.compensatedWorld), snapshot);
        if (!result.isSuccess()) {
            if (player.debugPlaces && result.getResyncReason() != null) {
                player.sendMessage("Resolved ignition failed: " + result.getResyncReason());
            }
            return false;
        }

        if (player.debugPlaces) {
            logResolvedChangeSet(player, result.getChangedBlocks());
        }

        return consumeInventory ? applyResolvedStates(place, result) : applyResolvedWorldStates(place, result);
    }

    public static boolean applyBucketPlace(CultPlayer player, BlockPlace place) {
        PlacementSnapshot snapshot = PlacementSnapshot.capture(player, place);
        PlacementResult result = simulateBucketPlace(PlacementBlockAccess.fromCompensatedWorld(player.compensatedWorld), snapshot);
        if (!result.isSuccess()) {
            if (player.debugPlaces && result.getResyncReason() != null) {
                player.sendMessage("Resolved bucket place failed: " + result.getResyncReason());
            }
            return false;
        }

        if (player.debugPlaces) {
            logResolvedChangeSet(player, result.getChangedBlocks());
        }

        applyResolvedWorldStates(place, result);
        return true;
    }

    public static Material applyBucketPickup(CultPlayer player, BlockPlace place) {
        PlacementSnapshot snapshot = PlacementSnapshot.capture(player, place);
        BucketPickupResult result = simulateBucketPickup(PlacementBlockAccess.fromCompensatedWorld(player.compensatedWorld), snapshot);
        if (!result.result().isSuccess()) {
            if (player.debugPlaces && result.result().getResyncReason() != null) {
                player.sendMessage("Resolved bucket pickup failed: " + result.result().getResyncReason());
            }
            return null;
        }

        if (player.debugPlaces) {
            logResolvedChangeSet(player, result.result().getChangedBlocks());
        }

        applyResolvedWorldStates(place, result.result());
        return SpigotConversionUtil.fromNmsItemStack(result.filledBucket()).getType();
    }

    public static boolean applyBlockUse(CultPlayer player, BlockPlace place) {
        PlacementSnapshot snapshot = PlacementSnapshot.capture(player, place);
        BlockUseResult result = simulateBlockUse(
                PlacementBlockAccess.fromCompensatedWorld(player.compensatedWorld),
                snapshot,
                player.canUseGameMasterBlocks()
        );
        if (!result.isSuccess()) {
            if (player.debugPlaces && result.resyncReason() != null) {
                player.sendMessage("Resolved block use failed: " + result.resyncReason());
            }
            return false;
        }

        if (player.debugPlaces) {
            logResolvedChangeSet(player, result.changedBlocks());
        }

        if (result.handAfter() != null) {
            player.getInventory().applyClientSideUseItemOnResult(
                    snapshot.getHand(),
                    SpigotConversionUtil.fromNmsItemStack(result.handAfter()),
                    result.addedItems().stream().map(SpigotConversionUtil::fromNmsItemStack).toList());
        } else if (result.consumeInventory()) {
            player.getInventory().onBlockPlace(place);
        }
        for (PlacementResult.ChangedBlock changedBlock : result.changedBlocks()) {
            place.applyResolvedSecondary(changedBlock.position(), SpigotConversionUtil.fromNmsBlockState(changedBlock.state()));
        }
        return true;
    }

    public static boolean applyWorldModifyingUseItem(CultPlayer player, BlockPlace place) {
        PlacementSnapshot snapshot = PlacementSnapshot.capture(player, place);
        PlacementResult result = simulateWorldModifyingUseItem(
                PlacementBlockAccess.fromCompensatedWorld(player.compensatedWorld),
                snapshot,
                mainHandUseIsBlockedByOffhand(player, snapshot)
        );
        if (!result.isSuccess()) {
            if (player.debugPlaces && result.getResyncReason() != null) {
                player.sendMessage("Resolved item use failed: " + result.getResyncReason());
            }
            return false;
        }

        if (player.debugPlaces) {
            logResolvedChangeSet(player, result.getChangedBlocks());
        }

        return result.isConsumeInventory() ? applyResolvedStates(place, result) : applyResolvedWorldStates(place, result);
    }

    public static boolean applyClientSideUseOnItem(CultPlayer player, BlockPlace place) {
        PlacementSnapshot snapshot = PlacementSnapshot.capture(player, place);
        ItemUseOnResult result = simulateClientSideUseOnItem(
                PlacementBlockAccess.fromCompensatedWorld(player.compensatedWorld),
                snapshot,
                player.getInventory().inventory.hasItemType(Material.GLASS_BOTTLE)
        );
        if (!result.isSuccess()) {
            if (player.debugPlaces && result.resyncReason() != null) {
                player.sendMessage("Resolved item useOn failed: " + result.resyncReason());
            }
            return false;
        }

        if (player.debugPlaces) {
            logResolvedChangeSet(player, result.changedBlocks());
        }

        applyResolvedWorldStates(place, PlacementResult.success(result.changedBlocks(), snapshot.getClickedBlockPos(), false));
        player.getInventory().applyClientSideUseItemOnResult(
                snapshot.getHand(),
                SpigotConversionUtil.fromNmsItemStack(result.handAfter()),
                result.addedItems().stream().map(SpigotConversionUtil::fromNmsItemStack).toList());
        return true;
    }

    public static boolean applyClientSideUseItem(CultPlayer player, InteractionHand hand, float yaw, float pitch) {
        org.bukkit.inventory.ItemStack bukkitHand = player.getInventory().getHandItem(hand);
        ItemStack useStack = SpigotConversionUtil.toNmsItemStack(bukkitHand);
        if (useStack.isEmpty() || useStack.getItem() instanceof BlockItem || useStack.getItem() instanceof BucketItem) {
            return false;
        }

        Vec3 clientPosition = new Vec3(
                player.packetStateData.clientSidePosition.x,
                player.packetStateData.clientSidePosition.y,
                player.packetStateData.clientSidePosition.z
        );
        BlockPos anchor = new BlockPos(Mth.floor(clientPosition.x), Mth.floor(clientPosition.y), Mth.floor(clientPosition.z));
        PlacementSnapshot snapshot = PlacementSnapshot.of(
                hand,
                bukkitHand,
                useStack,
                SpigotConversionUtil.toNmsItemStack(player.getInventory().getHeldItem()),
                SpigotConversionUtil.toNmsItemStack(player.getInventory().getOffHand()),
                anchor,
                anchor,
                Direction.UP,
                clientPosition,
                new Vec3(0.5D, 0.5D, 0.5D),
                false,
                clientPosition,
                pitch,
                yaw,
                Direction.fromYRot(yaw),
                player.isSneaking,
                player.gamemode,
                player.food,
                player.compensatedWorld.getMinHeight(),
                player.compensatedWorld.getMaxHeight(),
                false
        );

        ItemUseResult result = simulateClientSideUseItem(PlacementBlockAccess.fromCompensatedWorld(player.compensatedWorld), snapshot);
        if (!result.isSuccess()) {
            if (player.debugPlaces && result.resyncReason() != null) {
                player.sendMessage("Resolved item use failed: " + result.resyncReason());
            }
            return false;
        }

        player.getInventory().applyClientSideUseItemOnResult(
                hand,
                SpigotConversionUtil.fromNmsItemStack(result.handAfter()),
                result.addedItems().stream().map(SpigotConversionUtil::fromNmsItemStack).toList());
        return true;
    }

    static PlacementResult simulatePlace(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        if (snapshot.getItemStack().isEmpty() || !(snapshot.getItemStack().getItem() instanceof BlockItem blockItem)) {
            return PlacementResult.failed("not a block item");
        }
        return simulatePlace(blockAccess, snapshot, blockItem);
    }

    static PlacementResult simulatePlace(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot, Block block) {
        if (!(block.asItem() instanceof BlockItem blockItem)) {
            return PlacementResult.failed("not a block item");
        }
        return simulatePlace(blockAccess, snapshot, blockItem);
    }

    static PlacementResult simulateIgnition(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        PlacementWorldAdapter world = PlacementWorldFactory.create(blockAccess, snapshot);
        BlockPos pos = snapshot.getClickedBlockPos();
        if (snapshot.isOutsideBuildHeight(pos)) {
            return PlacementResult.failed("outside build height");
        }

        BlockState blockState = world.getBlockState(pos);
        if (CampfireBlock.canLight(blockState) || CandleBlock.canLight(blockState) || CandleCakeBlock.canLight(blockState)) {
            world.setBlock(pos, blockState.setValue(BlockStateProperties.LIT, true), PLACEMENT_FLAGS, MAX_UPDATE_DEPTH);
            return world.buildResult(pos);
        }

        BlockPos firePos = pos.relative(snapshot.getClickedFace());
        if (snapshot.isOutsideBuildHeight(firePos)) {
            return PlacementResult.failed("outside build height");
        }

        if (!BaseFireBlock.canBePlacedAt(world.level(), firePos, snapshot.getHorizontalDirection())) {
            return PlacementResult.failed("fire cannot survive");
        }

        world.setBlock(firePos, BaseFireBlock.getState(world.level(), firePos), PLACEMENT_FLAGS, MAX_UPDATE_DEPTH);
        return world.buildResult(firePos);
    }

    static PlacementResult simulateBucketPlace(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        if (snapshot.getItemStack().isEmpty() || !(snapshot.getItemStack().getItem() instanceof BucketItem bucketItem)) {
            return PlacementResult.failed("not a fluid bucket");
        }
        if (bucketItem.getContent() == Fluids.EMPTY) {
            return PlacementResult.failed("empty bucket cannot place fluid");
        }

        PlacementWorldAdapter world = PlacementWorldFactory.create(blockAccess, snapshot);
        BlockPos clickedPos = snapshot.getClickedBlockPos();
        BlockState clickedState = world.getBlockState(clickedPos);
        BlockPos placePos = clickedState.getBlock() instanceof LiquidBlockContainer && bucketItem.getContent() == Fluids.WATER
                ? clickedPos
                : clickedPos.relative(snapshot.getClickedFace());

        if (snapshot.isOutsideBuildHeight(placePos)) {
            return PlacementResult.failed("outside build height");
        }

        // Paper's BucketItem#use fires Bukkit events around emptyContents. Calling emptyContents
        // directly with no entity keeps the vanilla fluid-placement rules without touching Bukkit.
        if (!bucketItem.emptyContents(null, world.level(), placePos, SnapshotBlockPlaceContext.createHitResult(snapshot))) {
            return PlacementResult.failed("bucket fluid cannot be placed");
        }

        return world.buildResult(placePos);
    }

    static BucketPickupResult simulateBucketPickup(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        PlacementWorldAdapter world = PlacementWorldFactory.create(blockAccess, snapshot);
        BlockPos pos = snapshot.getClickedBlockPos();
        if (snapshot.isOutsideBuildHeight(pos)) {
            return BucketPickupResult.failed("outside build height");
        }

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BucketPickup bucketPickup)) {
            return BucketPickupResult.failed("target is not bucket-pickup capable");
        }

        ItemStack filledBucket = bucketPickup.pickupBlock(null, world.level(), pos, state);
        if (filledBucket.isEmpty()) {
            return BucketPickupResult.failed("bucket pickup returned empty");
        }

        return BucketPickupResult.success(world.buildResult(pos), filledBucket);
    }

    static BlockUseResult simulateBlockUse(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot, boolean canUseGameMasterBlocks) {
        PlacementWorldAdapter world = PlacementWorldFactory.create(blockAccess, snapshot);
        BlockPos pos = snapshot.getClickedBlockPos();
        if (snapshot.isOutsideBuildHeight(pos)) {
            return BlockUseResult.failed("outside build height");
        }

        BlockState state = world.getBlockState(pos);
        BlockUseResult paperPatchedResult = PaperPatchedCakeUseResolver.trySimulate(world, snapshot, state, pos);
        if (paperPatchedResult != null) {
            return paperPatchedResult;
        }
        // MCP-Reborn c59f05e TntBlock.java:101-128 consumes ignition on the client,
        // while prime() at lines 84-97 mutates only a ServerLevel.
        if (state.getBlock() == Blocks.TNT && (snapshot.getItemStack().is(Items.FLINT_AND_STEEL)
                || snapshot.getItemStack().is(Items.FIRE_CHARGE))) {
            return BlockUseResult.success(List.of(), false);
        }
        BlockHitResult hitResult = SnapshotBlockPlaceContext.createHitResult(snapshot);

        InteractionResult result = NmsClientInteraction.paperPatchedBlockUse(
                state,
                world.level(),
                pos
        );
        if (result == null) {
            result = NmsClientInteraction.playerFreeBlockUse(state, world.level(), pos, snapshot);
        }
        if (result == null && snapshot.getItemStack().isEmpty()) {
            // MCP-Reborn c59f05e DoorBlock.java:200-209, TrapDoorBlock.java:90-98,
            // and LeverBlock.java:63-68 are player-free client state transitions.
            result = NmsClientInteraction.playerFreeEmptyHandUse(state, world.level(), hitResult);
        }
        if (result == null) {
            // MCP-Reborn c59f05e EnderChestBlock.java:79-100 and
            // DaylightDetectorBlock.java:78-92: the player-dependent work either
            // is server-only or selects SUCCESS without client world mutation.
            result = NmsClientInteraction.playerDependentClientNoop(state, snapshot);
        }
        if (result == null && snapshot.getItemStack().getItem() instanceof BlockItem) {
            // MCP-Reborn c59f05e MultiPlayerGameMode.java:348-357.
            // A BlockItem first asks the target block to consume the click. The
            // detached client level and null player keep this boundary incapable
            // of reaching a runtime player; other item/player interactions fail closed.
            InteractionResult itemUse = state.useItemOn(snapshot.getItemStack(), world.level(), null, snapshot.getHand(), hitResult);
            if (itemUse instanceof InteractionResult.TryEmptyHandInteraction
                    && snapshot.getHand() == InteractionHand.MAIN_HAND) {
                result = state.useWithoutItem(world.level(), null, hitResult);
            } else if (!itemUse.consumesAction()) {
                result = itemUse;
            }
        }
        if (result == null) {
            return BlockUseResult.failed("block use requires entity or inventory behavior");
        }
        if (!result.consumesAction()) {
            return BlockUseResult.failed("block use passed");
        }

        return BlockUseResult.success(world.buildResult(pos).getChangedBlocks(), false);
    }

    static PlacementResult simulateWorldModifyingUseItem(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot, boolean mainHandBlockedByOffhand) {
        if (snapshot.getItemStack().isEmpty()) {
            return PlacementResult.failed("empty item");
        }

        PlacementWorldAdapter world = PlacementWorldFactory.create(blockAccess, snapshot);
        BlockPos pos = snapshot.getClickedBlockPos();
        if (snapshot.isOutsideBuildHeight(pos)) {
            return PlacementResult.failed("outside build height");
        }

        ItemStack itemStack = snapshot.getItemStack();
        if (itemStack.getItem() instanceof AxeItem) {
            if (mainHandBlockedByOffhand) {
                return PlacementResult.failed("main-hand axe use blocked by offhand item");
            }
            return simulateAxeUse(world, pos);
        }
        if (itemStack.getItem() instanceof HoneycombItem) {
            Optional<BlockState> waxed = HoneycombItem.getWaxed(world.getBlockState(pos));
            if (waxed.isEmpty()) {
                return PlacementResult.failed("target cannot be waxed");
            }
            world.setBlock(pos, waxed.get(), PLACEMENT_FLAGS, MAX_UPDATE_DEPTH);
            return PlacementResult.success(world.buildResult(pos).getChangedBlocks(), pos, true);
        }

        return PlacementResult.failed("item has no client-side world mutation");
    }

    static ItemUseOnResult simulateClientSideUseOnItem(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        return simulateClientSideUseOnItem(blockAccess, snapshot, false);
    }

    static ItemUseOnResult simulateClientSideUseOnItem(
            PlacementBlockAccess blockAccess,
            PlacementSnapshot snapshot,
            boolean creativeHasBottle
    ) {
        ItemStack itemStack = snapshot.getItemStack();
        if (itemStack.isEmpty()) {
            return ItemUseOnResult.failed("empty item");
        }
        if (itemStack.getItem() instanceof BlockItem) {
            return ItemUseOnResult.failed("block item handled by block placement");
        }
        if (snapshot.isOutsideBuildHeight(snapshot.getClickedBlockPos())) {
            return ItemUseOnResult.failed("outside build height");
        }
        // MCP-Reborn c59f05e PotionItem.java:35-66, inside the prediction window at
        // MultiPlayerGameMode.java:320-329 and 361-370.
        if (itemStack.getItem() instanceof PotionItem
                && snapshot.getClickedFace() != Direction.DOWN
                && itemStack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER)
                && blockAccess.getBlockStateAt(snapshot.getClickedBlockPos()).is(BlockTags.CONVERTABLE_TO_MUD)) {
            PlacementWorldAdapter world = PlacementWorldFactory.create(blockAccess, snapshot);
            world.setBlock(snapshot.getClickedBlockPos(), Blocks.MUD.defaultBlockState(), 3, MAX_UPDATE_DEPTH);

            ItemStack handAfter = itemStack.copy();
            List<ItemStack> addedItems = List.of();
            if (snapshot.isCreative()) {
                if (!creativeHasBottle) addedItems = List.of(new ItemStack(Items.GLASS_BOTTLE));
            } else if (handAfter.getCount() == 1) {
                handAfter = new ItemStack(Items.GLASS_BOTTLE);
            } else {
                handAfter.shrink(1);
                addedItems = List.of(new ItemStack(Items.GLASS_BOTTLE));
            }
            return ItemUseOnResult.success(
                    world.buildResult(snapshot.getClickedBlockPos()).getChangedBlocks(),
                    handAfter,
                    addedItems
            );
        }
        // Entity-placement items consult or construct runtime entities. The detached
        // predictor has no entity capability, so authoritative spawn/ack reconciles them.
        return ItemUseOnResult.failed("item useOn is not safe for detached prediction");
    }

    static ItemUseResult simulateClientSideUseItem(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        ItemStack snapshotStack = snapshot.getItemStack();
        if (snapshotStack.isEmpty()) {
            return ItemUseResult.failed("empty item");
        }

        // BoatItem.java:31-60 constructs and collides an entity even on the client path.
        ItemStack itemStack = snapshotStack.copy();
        if (NmsClientInteraction.clientConsumesHeldUse(itemStack.getItem())) {
            if (!snapshot.isCreative()) {
                itemStack.shrink(1);
            }
            return ItemUseResult.success(itemStack, List.of());
        }
        return ItemUseResult.failed("held item use is not safe for detached prediction");
    }

    private static PlacementResult simulateAxeUse(PlacementWorldAdapter world, BlockPos pos) {
        BlockState oldState = world.getBlockState(pos);
        Optional<BlockState> newState = getStripped(oldState)
                .or(() -> WeatheringCopper.getPrevious(oldState))
                .or(() -> getWaxedOff(oldState));
        if (newState.isEmpty()) {
            return PlacementResult.failed("target cannot be modified by axe");
        }

        world.setBlock(pos, newState.get(), PLACEMENT_FLAGS, MAX_UPDATE_DEPTH);
        return PlacementResult.success(world.buildResult(pos).getChangedBlocks(), pos, false);
    }

    private static Optional<BlockState> getStripped(BlockState state) {
        Block stripped = NmsClientInteraction.strippedBlock(state.getBlock());
        if (stripped == null) {
            return Optional.empty();
        }
        return Optional.of(stripped.defaultBlockState().setValue(RotatedPillarBlock.AXIS, state.getValue(RotatedPillarBlock.AXIS)));
    }

    private static Optional<BlockState> getWaxedOff(BlockState state) {
        return Optional.ofNullable(HoneycombItem.WAX_OFF_BY_BLOCK.get().get(state.getBlock())).map(block -> block.withPropertiesOf(state));
    }

    private static boolean mainHandUseIsBlockedByOffhand(CultPlayer player, PlacementSnapshot snapshot) {
        if (snapshot.getHand() != InteractionHand.MAIN_HAND || snapshot.isSecondaryUse()) {
            return false;
        }

        ItemStack offhand = SpigotConversionUtil.toNmsItemStack(player.getInventory().getOffHand());
        return BLOCKS_ATTACKS_COMPONENT == null
                ? offhand.getItem() == Items.SHIELD
                : offhand.has(BLOCKS_ATTACKS_COMPONENT);
    }

    private static DataComponentType<?> findDataComponent(String fieldName) {
        try {
            return (DataComponentType<?>) DataComponents.class.getField(fieldName).get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static PlacementResult simulatePlace(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot, BlockItem blockItem) {
        PlacementWorldAdapter world = PlacementWorldFactory.create(blockAccess, snapshot);
        SnapshotBlockPlaceContext initialContext = SnapshotBlockPlaceContext.create(world.level(), snapshot);
        if (!initialContext.canPlace()) {
            return PlacementResult.failed("placement context rejected target");
        }

        BlockPlaceContext updatedContext = blockItem.updatePlacementContext(initialContext);
        if (updatedContext == null) {
            return PlacementResult.failed("unsupported placement context");
        }

        SnapshotBlockPlaceContext placementContext = SnapshotBlockPlaceContext.wrap(updatedContext, snapshot);
        BlockPos primaryPos = placementContext.getClickedPos();
        if (snapshot.isOutsideBuildHeight(primaryPos)) {
            return PlacementResult.failed("outside build height");
        }

        BlockState resolvedState = NmsClientInteraction.placementState(blockItem, placementContext);
        if (resolvedState == null) {
            return PlacementResult.failed("no placement state");
        }

        if (!NmsClientInteraction.place(blockItem, placementContext, resolvedState)) {
            return PlacementResult.failed("virtual setBlock failed");
        }

        PlacementResult result = world.buildResult(primaryPos);
        if (result.getChangedBlocks().isEmpty()) {
            return PlacementResult.failed("no compensated changes emitted");
        }
        return result;
    }

    private static void logResolvedChangeSet(CultPlayer player, List<PlacementResult.ChangedBlock> changedBlocks) {
        int index = 0;
        for (PlacementResult.ChangedBlock changedBlock : changedBlocks) {
            BlockData data = SpigotConversionUtil.fromNmsBlockState(changedBlock.state());
            player.sendMessage("Resolved[" + index + "] " + data.getAsString(false) + " at " + changedBlock.position());
            index++;
        }
    }

    private static boolean applyResolvedStates(BlockPlace place, PlacementResult result) {
        PlacementResult.ChangedBlock primary = result.getChangedBlocks().getFirst();
        if (!place.applyResolvedPrimary(primary.position(), SpigotConversionUtil.fromNmsBlockState(primary.state()))) {
            return false;
        }

        for (int i = 1; i < result.getChangedBlocks().size(); i++) {
            PlacementResult.ChangedBlock changedBlock = result.getChangedBlocks().get(i);
            place.applyResolvedSecondary(changedBlock.position(), SpigotConversionUtil.fromNmsBlockState(changedBlock.state()));
        }

        return true;
    }

    private static boolean applyResolvedWorldStates(BlockPlace place, PlacementResult result) {
        for (PlacementResult.ChangedBlock changedBlock : result.getChangedBlocks()) {
            place.applyResolvedSecondary(changedBlock.position(), SpigotConversionUtil.fromNmsBlockState(changedBlock.state()));
        }
        return true;
    }

    record BucketPickupResult(PlacementResult result, ItemStack filledBucket) {
        private static BucketPickupResult success(PlacementResult result, ItemStack filledBucket) {
            return new BucketPickupResult(result, filledBucket);
        }

        private static BucketPickupResult failed(String reason) {
            return new BucketPickupResult(PlacementResult.failed(reason), ItemStack.EMPTY);
        }
    }

    record BlockUseResult(
            boolean isSuccess,
            List<PlacementResult.ChangedBlock> changedBlocks,
            boolean consumeInventory,
            ItemStack handAfter,
            List<ItemStack> addedItems,
            String resyncReason
    ) {
        static BlockUseResult success(List<PlacementResult.ChangedBlock> changedBlocks, boolean consumeInventory) {
            return new BlockUseResult(true, List.copyOf(changedBlocks), consumeInventory, null, List.of(), null);
        }

        static BlockUseResult success(List<PlacementResult.ChangedBlock> changedBlocks, ItemStack handAfter, List<ItemStack> addedItems, boolean consumeInventory) {
            return new BlockUseResult(true, List.copyOf(changedBlocks), consumeInventory, handAfter.copy(), List.copyOf(addedItems), null);
        }

        static BlockUseResult failed(String reason) {
            return new BlockUseResult(false, List.of(), false, null, List.of(), reason);
        }
    }

    record ItemUseOnResult(
            boolean isSuccess,
            List<PlacementResult.ChangedBlock> changedBlocks,
            ItemStack handAfter,
            List<ItemStack> addedItems,
            String resyncReason
    ) {
        static ItemUseOnResult success(List<PlacementResult.ChangedBlock> changedBlocks, ItemStack handAfter, List<ItemStack> addedItems) {
            return new ItemUseOnResult(true, List.copyOf(changedBlocks), handAfter.copy(), List.copyOf(addedItems), null);
        }

        static ItemUseOnResult failed(String reason) {
            return new ItemUseOnResult(false, List.of(), ItemStack.EMPTY, List.of(), reason);
        }
    }

    record ItemUseResult(
            boolean isSuccess,
            ItemStack handAfter,
            List<ItemStack> addedItems,
            String resyncReason
    ) {
        static ItemUseResult success(ItemStack handAfter, List<ItemStack> addedItems) {
            return new ItemUseResult(true, handAfter.copy(), List.copyOf(addedItems), null);
        }

        static ItemUseResult failed(String reason) {
            return new ItemUseResult(false, ItemStack.EMPTY, List.of(), reason);
        }
    }

    private static final class SnapshotBlockPlaceContext extends BlockPlaceContext {
        private static final ThreadLocal<PlacementSnapshot> CONSTRUCTION_SNAPSHOT = new ThreadLocal<>();

        private final PlacementSnapshot snapshot;

        private SnapshotBlockPlaceContext(net.minecraft.world.level.Level world, PlacementSnapshot snapshot) {
            super(world, null, snapshot.getHand(), snapshot.getItemStack(), createHitResult(snapshot));
            this.snapshot = snapshot;
        }

        private SnapshotBlockPlaceContext(BlockPlaceContext context, PlacementSnapshot snapshot) {
            super(context);
            this.snapshot = snapshot;
        }

        private static SnapshotBlockPlaceContext create(net.minecraft.world.level.Level world, PlacementSnapshot snapshot) {
            CONSTRUCTION_SNAPSHOT.set(snapshot);
            try {
                return new SnapshotBlockPlaceContext(world, snapshot);
            } finally {
                CONSTRUCTION_SNAPSHOT.remove();
            }
        }

        private static SnapshotBlockPlaceContext wrap(BlockPlaceContext context, PlacementSnapshot snapshot) {
            if (context instanceof SnapshotBlockPlaceContext snapshotContext) {
                return snapshotContext;
            }

            CONSTRUCTION_SNAPSHOT.set(snapshot);
            try {
                return new SnapshotBlockPlaceContext(context, snapshot);
            } finally {
                CONSTRUCTION_SNAPSHOT.remove();
            }
        }

        @Override
        public Direction getHorizontalDirection() {
            return snapshot().getHorizontalDirection();
        }

        @Override
        public boolean isSecondaryUseActive() {
            return snapshot().isSecondaryUse();
        }

        @Override
        public float getRotation() {
            return snapshot().getYRot();
        }

        @Override
        public Direction getNearestLookingDirection() {
            return orderedByNearest()[0];
        }

        @Override
        public Direction getNearestLookingVerticalDirection() {
            return snapshot().getXRot() < 0.0F ? Direction.UP : Direction.DOWN;
        }

        @Override
        public Direction[] getNearestLookingDirections() {
            Direction[] ordered = orderedByNearest();
            if (replacingClickedOnBlock()) {
                return ordered;
            }

            Direction opposite = getClickedFace().getOpposite();
            int index = 0;
            while (index < ordered.length && ordered[index] != opposite) {
                index++;
            }
            if (index > 0 && index < ordered.length) {
                System.arraycopy(ordered, 0, ordered, 1, index);
                ordered[0] = opposite;
            }
            return ordered;
        }

        private PlacementSnapshot snapshot() {
            PlacementSnapshot current = snapshot;
            if (current != null) {
                return current;
            }

            PlacementSnapshot constructing = CONSTRUCTION_SNAPSHOT.get();
            if (constructing == null) {
                throw new IllegalStateException("Missing placement snapshot during context construction");
            }
            return constructing;
        }

        private Direction[] orderedByNearest() {
            PlacementSnapshot snapshot = snapshot();
            float xRot = snapshot.getXRot() * ((float) Math.PI / 180F);
            float yRot = -snapshot.getYRot() * ((float) Math.PI / 180F);
            float sinX = Mth.sin(xRot);
            float cosX = Mth.cos(xRot);
            float sinY = Mth.sin(yRot);
            float cosY = Mth.cos(yRot);
            boolean east = sinY > 0.0F;
            boolean up = sinX < 0.0F;
            boolean south = cosY > 0.0F;
            float xMagnitude = east ? sinY : -sinY;
            float yMagnitude = up ? -sinX : sinX;
            float zMagnitude = south ? cosY : -cosY;
            float xHorizontal = xMagnitude * cosX;
            float zHorizontal = zMagnitude * cosX;
            Direction xDirection = east ? Direction.EAST : Direction.WEST;
            Direction yDirection = up ? Direction.UP : Direction.DOWN;
            Direction zDirection = south ? Direction.SOUTH : Direction.NORTH;

            if (xMagnitude > zMagnitude) {
                if (yMagnitude > xHorizontal) {
                    return makeDirectionArray(yDirection, xDirection, zDirection);
                }
                if (zHorizontal > yMagnitude) {
                    return makeDirectionArray(xDirection, zDirection, yDirection);
                }
                return makeDirectionArray(xDirection, yDirection, zDirection);
            }

            if (yMagnitude > zHorizontal) {
                return makeDirectionArray(yDirection, zDirection, xDirection);
            }
            if (xHorizontal > yMagnitude) {
                return makeDirectionArray(zDirection, xDirection, yDirection);
            }
            return makeDirectionArray(zDirection, yDirection, xDirection);
        }

        private static Direction[] makeDirectionArray(Direction first, Direction second, Direction third) {
            return new Direction[] {
                    first,
                    second,
                    third,
                    third.getOpposite(),
                    second.getOpposite(),
                    first.getOpposite()
            };
        }

        private static BlockHitResult createHitResult(PlacementSnapshot snapshot) {
            return new BlockHitResult(
                    snapshot.getClickLocation(),
                    snapshot.getClickedFace(),
                    snapshot.getClickedBlockPos(),
                    snapshot.isInsideBlock()
            );
        }
    }
}
