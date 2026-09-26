package ac.cult.cultac.utils.blockplace;

import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.GameMasterBlockItem;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ScaffoldingBlockItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Thin access boundary around client behavior patched by Paper.
 * MCP-Reborn c59f05e: BlockItem.java:41-45; StandingAndWallBlockItem.java:23-44;
 * ConcretePowderBlock.java:44-76; FenceGateBlock.java:147-168; RepeaterBlock.java:40-48;
 * ComparatorBlock.java:131-140; ComposterBlock.java:273-303; ButtonBlock.java:88-115.
 */
final class NmsClientInteraction {
    // wallBlock was public before 26.2 and is protected now; read it reflectively.
    private static final java.lang.reflect.Field STANDING_AND_WALL_BLOCK = resolveWallBlockField();
    private static final java.lang.reflect.Method WIRE_CONNECTION_STATE = resolveWireConnectionState();

    private NmsClientInteraction() {
    }

    private static java.lang.reflect.Field resolveWallBlockField() {
        try {
            java.lang.reflect.Field field = StandingAndWallBlockItem.class.getDeclaredField("wallBlock");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Block wallBlock(StandingAndWallBlockItem item) {
        try {
            return (Block) STANDING_AND_WALL_BLOCK.get(item);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static java.lang.reflect.Method resolveWireConnectionState() {
        try {
            // The wire class was renamed in 26.3. Resolve from the registered
            // block, and reuse its read-only shape calculation on our overlay.
            java.lang.reflect.Method method = Blocks.REDSTONE_WIRE.getClass().getDeclaredMethod(
                    "getConnectionState", BlockGetter.class, BlockState.class, BlockPos.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static BlockState wireConnectionState(BlockState state, Level level, BlockPos pos) {
        try {
            return (BlockState) WIRE_CONNECTION_STATE.invoke(Blocks.REDSTONE_WIRE, level, state, pos);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static @Nullable BlockState placementState(BlockItem item, BlockPlaceContext context) {
        // MushroomBlock.java:83-86 consults client light, which Cult does not retain.
        // Decline prediction instead of fabricating light or reading the live world.
        if (item.getBlock() instanceof MushroomBlock) {
            return null;
        }
        if (item.getBlock() instanceof ConcretePowderBlock) {
            return concretePowderState(item.getBlock(), context);
        }
        if (item instanceof StandingAndWallBlockItem standingAndWall) {
            return standingAndWallState(standingAndWall, context);
        }
        if (item instanceof GameMasterBlockItem && context.getPlayer() != null
                && !context.getPlayer().canUseGameMasterBlocks()) {
            return null;
        }

        BlockState state = ClientBlockPlacement.state(item.getBlock(), context);
        if (state == null) state = item.getBlock().getStateForPlacement(context);
        return state != null
                && (item instanceof ScaffoldingBlockItem || ClientBlockPlacement.canSurvive(state, context.getLevel(), context.getClickedPos()))
                && context.getLevel().isUnobstructed(state, context.getClickedPos(), CollisionContext.empty())
                ? state
                : null;
    }

    private static @Nullable BlockState standingAndWallState(StandingAndWallBlockItem item, BlockPlaceContext context) {
        BlockState wallState = wallBlock(item).getStateForPlacement(context);
        Direction attachment = item instanceof HangingSignItem ? Direction.UP : Direction.DOWN;
        for (Direction direction : context.getNearestLookingDirections()) {
            if (direction == attachment.getOpposite()) continue;
            BlockState candidate = direction == attachment ? item.getBlock().getStateForPlacement(context) : wallState;
            if (candidate != null && canPlaceStandingAndWall(item, candidate, context)) {
                return context.getLevel().isUnobstructed(candidate, context.getClickedPos(), CollisionContext.empty())
                        ? candidate : null;
            }
        }
        return null;
    }

    private static boolean canPlaceStandingAndWall(
            StandingAndWallBlockItem item,
            BlockState state,
            BlockPlaceContext context
    ) {
        return (!(item instanceof HangingSignItem) || !(state.getBlock() instanceof WallHangingSignBlock wall)
                || wall.canPlace(state, context.getLevel(), context.getClickedPos()))
                && state.canSurvive(context.getLevel(), context.getClickedPos());
    }

    private static BlockState concretePowderState(Block powder, BlockPlaceContext context) {
        BlockState replaced = context.getLevel().getBlockState(context.getClickedPos());
        for (Direction direction : Direction.values()) {
            if (direction == Direction.DOWN && !replaced.getFluidState().is(FluidTags.WATER)) continue;
            BlockState neighbor = context.getLevel().getBlockState(context.getClickedPos().relative(direction));
            if (neighbor.getFluidState().is(FluidTags.WATER)
                    && !neighbor.isFaceSturdy(context.getLevel(), context.getClickedPos(), direction.getOpposite())) {
                return concreteFor(powder).defaultBlockState();
            }
        }
        return replaced.getFluidState().is(FluidTags.WATER) ? concreteFor(powder).defaultBlockState() : powder.defaultBlockState();
    }

    private static Block concreteFor(Block powder) {
        String id = NmsIdentifierUtil.registryKey(BuiltInRegistries.BLOCK, powder);
        String path = id.substring(id.indexOf(':') + 1);
        return NmsIdentifierUtil.registryValue(BuiltInRegistries.BLOCK,
                "minecraft:" + path.substring(0, path.length() - "_powder".length()));
    }

    static BlockState concretePowderNeighborShape(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        // MCP ConcretePowderBlock#touchesLiquid/updateShape. CraftBukkit wraps
        // this state conversion in BlockFormEvent even on a client-side Level.
        BlockPos.MutableBlockPos testPos = pos.mutable();
        for (Direction direction : Direction.values()) {
            if (direction != Direction.DOWN || level.getBlockState(testPos).getFluidState().is(FluidTags.WATER)) {
                testPos.setWithOffset(pos, direction);
                BlockState neighbor = level.getBlockState(testPos);
                if (neighbor.getFluidState().is(FluidTags.WATER)
                        && !neighbor.isFaceSturdy(level, pos, direction.getOpposite())) {
                    return concreteFor(state.getBlock()).defaultBlockState();
                }
            }
        }
        return state; // FallingBlock's remaining update only schedules a server tick.
    }

    static boolean place(BlockItem item, BlockPlaceContext context, BlockState state, PlacementSnapshot snapshot) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (item instanceof DoubleHighBlockItem) {
            level.setBlock(pos.above(), level.isWaterAt(pos.above())
                    ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 27);
        }
        boolean placed = level.setBlock(pos, state, item.getClass().getName().equals("net.minecraft.world.item.BedItem") ? 26 : 11);
        if (placed && state.hasProperty(BlockStateProperties.BED_PART)) {
            var version = snapshot.getClientVersion();
            if (version == null) {
                version = ac.cult.cultac.network.protocol.ClientVersion.fromProtocolVersion(net.minecraft.SharedConstants.getProtocolVersion());
            }
            // 26.2 BedBlock#setPlacedBy (26.3 AbstractBedBlock) also places the
            // head on the client. Older clients wait for the server's update.
            if (version.isNewerThanOrEquals(ac.cult.cultac.network.protocol.ClientVersion.V_26_2)) {
                level.setBlock(pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)),
                        state.setValue(BlockStateProperties.BED_PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
            }
        }
        if (placed && item instanceof DoubleHighBlockItem) {
            BlockState upper = state.getBlock() instanceof DoublePlantBlock plant
                    ? DoublePlantBlock.copyWaterloggedFrom(level, pos.above(),
                    plant.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER))
                    : state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                    DoubleBlockHalf.UPPER);
            level.setBlock(pos.above(), upper, 3);
        }
        return placed;
    }

    static @Nullable Block strippedBlock(Block block) {
        String id = NmsIdentifierUtil.registryKey(BuiltInRegistries.BLOCK, block);
        int separator = id.indexOf(':') + 1;
        return NmsIdentifierUtil.registryOptional(BuiltInRegistries.BLOCK,
                id.substring(0, separator) + "stripped_" + id.substring(separator)).orElse(null);
    }

    static boolean clientConsumesHeldUse(Item item) {
        // MCP-Reborn c59f05e: SnowballItem.java:24-42, EggItem.java:24-42,
        // EnderpearlItem.java:22-40, ExperienceBottleItem.java:22-40,
        // ThrowablePotionItem.java:23-31, and WindChargeItem.java:27-53.
        return item instanceof SnowballItem
                || item instanceof EggItem
                || item instanceof EnderpearlItem
                || item instanceof ExperienceBottleItem
                || item instanceof ThrowablePotionItem
                || item instanceof WindChargeItem;
    }

    static @Nullable InteractionResult paperPatchedBlockUse(BlockState state, Level level, BlockPos pos) {
        if (state.getBlock() instanceof JukeboxBlock && state.getValue(JukeboxBlock.HAS_RECORD)) {
            return InteractionResult.SUCCESS;
        }
        if (!(state.getBlock() instanceof ButtonBlock button)) return null;
        if (state.getValue(ButtonBlock.POWERED)) return InteractionResult.CONSUME;
        button.press(state, level, pos, null);
        return InteractionResult.SUCCESS;
    }

    static @Nullable InteractionResult playerFreeBlockUse(
            BlockState state,
            Level level,
            BlockPos pos,
            PlacementSnapshot snapshot,
            BlockHitResult hitResult,
            boolean canUseGameMasterBlocks
    ) {
        Block block = state.getBlock();
        if (block instanceof FenceGateBlock) {
            Direction facing = snapshot.getHorizontalDirection();
            if (!state.getValue(FenceGateBlock.OPEN)
                    && state.getValue(FenceGateBlock.FACING) == facing.getOpposite()) {
                state = state.setValue(FenceGateBlock.FACING, facing);
            }
            level.setBlock(pos, state.cycle(FenceGateBlock.OPEN), 10);
            return InteractionResult.SUCCESS;
        }
        if (block instanceof FlowerPotBlock pot && flowerPotPlacementState(pot, snapshot) != null) {
            level.setBlock(pos, flowerPotPlacementState(pot, snapshot), 3);
            return InteractionResult.SUCCESS;
        }
        if (block instanceof BellBlock) {
            // BellBlock's client path only rings particles/sounds. The state and
            // block entity change are authoritative server updates.
            return isBellHit(state, hitResult) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (block instanceof DragonEggBlock) {
            // DragonEggBlock chooses a random client-side destination. Do not
            // call its runtime teleport path; the server update supplies the
            // authoritative destination after the consumed click.
            return InteractionResult.SUCCESS;
        }
        if (block == Blocks.REDSTONE_WIRE) {
            if (snapshot.getGameMode() == org.bukkit.GameMode.ADVENTURE
                    || snapshot.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || !isRedstoneDotOrCross(state)) {
                return InteractionResult.PASS;
            }
            // Vanilla RedStoneWireBlock#useWithoutItem: toggle dot/cross, then
            // recompute actual connections while preserving the current power.
            RedstoneSide side = redstoneSideConnected(state, BlockStateProperties.NORTH_REDSTONE)
                    ? RedstoneSide.NONE : RedstoneSide.SIDE;
            BlockState toggled = block.defaultBlockState()
                    .setValue(BlockStateProperties.NORTH_REDSTONE, side)
                    .setValue(BlockStateProperties.SOUTH_REDSTONE, side)
                    .setValue(BlockStateProperties.EAST_REDSTONE, side)
                    .setValue(BlockStateProperties.WEST_REDSTONE, side)
                    .setValue(BlockStateProperties.POWER, state.getValue(BlockStateProperties.POWER));
            BlockState updated = wireConnectionState(toggled, level, pos);
            if (updated == state) return InteractionResult.PASS;
            level.setBlock(pos, updated, 3);
            // updatesOnShapeChange only sends neighbor notifications, which
            // are no-ops on ClientLevel; it does not propagate redstone power.
            return InteractionResult.SUCCESS;
        }
        if (block instanceof SignBlock || block instanceof LightBlock) {
            // Sign and light useWithoutItem are consumed on the client; their
            // editing/level changes are server-side.
            return InteractionResult.CONSUME;
        }
        if (block instanceof GameMasterBlock) {
            return canUseGameMasterBlocks ? InteractionResult.SUCCESS : null;
        }
        if (block instanceof RespawnAnchorBlock) {
            // MCP RespawnAnchorBlock#useItemOn checks offhand fuel before the
            // main-hand empty-item retry. Read the snapshot, never a live/null player.
            boolean canCharge = state.getValue(RespawnAnchorBlock.CHARGE) < RespawnAnchorBlock.MAX_CHARGES;
            if (snapshot.getItemStack().getItem() == net.minecraft.world.item.Items.GLOWSTONE && canCharge) {
                return null; // playerItemUse performs the client-side charge.
            }
            if (snapshot.getHand() == InteractionHand.MAIN_HAND && canCharge
                    && snapshot.getOffHandItemStack().getItem() == net.minecraft.world.item.Items.GLOWSTONE) {
                return InteractionResult.PASS;
            }
            return state.getValue(RespawnAnchorBlock.CHARGE) > RespawnAnchorBlock.MIN_CHARGES
                    ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        if (block instanceof DecoratedPotBlock) {
            // A client-side decorated-pot block entity consumes the click and
            // only the server mutates its contents.
            return InteractionResult.SUCCESS;
        }
        if (block instanceof ChiseledBookShelfBlock && snapshot.getItemStack().isEmpty()
                && hasSelectableSlot(state, hitResult)) {
            return InteractionResult.SUCCESS;
        }
        if (isSelectableContainer(block) && !snapshot.getItemStack().isEmpty()
                && hasSelectableSlot(state, hitResult)) {
            // ShelfBlock and ChiseledBookShelfBlock consume any non-empty
            // selected item when a slot is hit; the inventory swap is
            // server-authoritative.
            return InteractionResult.SUCCESS;
        }
        if (block instanceof VaultBlock
                && state.getValue(VaultBlock.STATE) == VaultState.ACTIVE
                && !snapshot.getItemStack().isEmpty()) {
            // Active vaults accept a key on the server; the client consumes the
            // click while the vault state and inventory remain authoritative.
            return InteractionResult.SUCCESS;
        }
        // MCP-Reborn's useWithoutItem implementations for these blocks only
        // open a screen or consume the interaction. Their client prediction
        // does not change the detached block state. Returning SUCCESS here is
        // also necessary before BlockItem placement is attempted: the client
        // first gives the clicked block a chance to consume an item-backed
        // click through MultiPlayerGameMode.
        if (isClientConsumedBlockUse(block)) {
            return InteractionResult.SUCCESS;
        }
        if (snapshot.getGameMode() == org.bukkit.GameMode.ADVENTURE
                || snapshot.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return block instanceof RepeaterBlock || block instanceof ComparatorBlock
                    ? InteractionResult.PASS : null;
        }
        if (block instanceof RepeaterBlock) {
            level.setBlock(pos, state.cycle(RepeaterBlock.DELAY), 3);
            return InteractionResult.SUCCESS;
        }
        if (block instanceof ComparatorBlock) {
            level.setBlock(pos, state.cycle(ComparatorBlock.MODE), 2);
            return InteractionResult.SUCCESS;
        }
        if (block instanceof ComposterBlock && state.getValue(ComposterBlock.LEVEL) == 8) {
            level.setBlock(pos, state.setValue(ComposterBlock.LEVEL, 0), 3);
            return InteractionResult.SUCCESS;
        }
        return null;
    }

    private static boolean isBellHit(BlockState state, BlockHitResult hitResult) {
        Direction clickedDirection = hitResult.getDirection();
        double clickY = hitResult.getLocation().y - hitResult.getBlockPos().getY();
        if (clickedDirection.getAxis() == Direction.Axis.Y || clickY > 0.8124F) {
            return false;
        }
        Direction facing = state.getValue(BellBlock.FACING);
        BellAttachType attachment = state.getValue(BellBlock.ATTACHMENT);
        return switch (attachment) {
            case FLOOR -> facing.getAxis() == clickedDirection.getAxis();
            case SINGLE_WALL, DOUBLE_WALL -> facing.getAxis() != clickedDirection.getAxis();
            case CEILING -> true;
        };
    }

    private static boolean isRedstoneDotOrCross(BlockState state) {
        return redstoneSideConnected(state, BlockStateProperties.NORTH_REDSTONE)
                && redstoneSideConnected(state, BlockStateProperties.SOUTH_REDSTONE)
                && redstoneSideConnected(state, BlockStateProperties.EAST_REDSTONE)
                && redstoneSideConnected(state, BlockStateProperties.WEST_REDSTONE)
                || !redstoneSideConnected(state, BlockStateProperties.NORTH_REDSTONE)
                && !redstoneSideConnected(state, BlockStateProperties.SOUTH_REDSTONE)
                && !redstoneSideConnected(state, BlockStateProperties.EAST_REDSTONE)
                && !redstoneSideConnected(state, BlockStateProperties.WEST_REDSTONE);
    }

    private static boolean redstoneSideConnected(
            BlockState state,
            net.minecraft.world.level.block.state.properties.EnumProperty<net.minecraft.world.level.block.state.properties.RedstoneSide> property
    ) {
        return state.getValue(property).isConnected();
    }

    private static boolean hasSelectableSlot(BlockState state, BlockHitResult hitResult) {
        // Both 1.21.3 ChiseledBookShelfBlock#getHitSlot and the later
        // SelectableSlotContainer clamp coordinates to a slot on the front face.
        // The interface itself does not exist on 1.21.3.
        return isSelectableContainer(state.getBlock())
                && hitResult.getDirection() == state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    private static boolean isSelectableContainer(Block block) {
        return block instanceof ChiseledBookShelfBlock
                || block.getClass().getName().equals("net.minecraft.world.level.block.ShelfBlock");
    }

    static boolean consumesHeldItem(BlockState state, PlacementSnapshot snapshot) {
        return state.getBlock() instanceof FlowerPotBlock pot && flowerPotPlacementState(pot, snapshot) != null;
    }

    private static @Nullable BlockState flowerPotPlacementState(FlowerPotBlock pot, PlacementSnapshot snapshot) {
        if (!(snapshot.getItemStack().getItem() instanceof BlockItem item) || pot.getPotted() != Blocks.AIR) {
            return null;
        }
        for (Block candidate : BuiltInRegistries.BLOCK) {
            if (candidate instanceof FlowerPotBlock candidatePot && candidatePot.getPotted() == item.getBlock()) {
                return candidate.defaultBlockState();
            }
        }
        return null;
    }

    private static boolean isClientConsumedBlockUse(Block block) {
        if (block instanceof BedBlock || block instanceof AnvilBlock || block instanceof ShulkerBoxBlock) {
            // BedBlock.useWithoutItem returns SUCCESS_SERVER on the client. It
            // consumes a held block click while leaving the bed state unchanged.
            return true;
        }
        return switch (NmsIdentifierUtil.registryKey(BuiltInRegistries.BLOCK, block)) {
            case "minecraft:crafting_table",
                    "minecraft:stonecutter",
                    "minecraft:loom",
                    "minecraft:cartography_table",
                    "minecraft:smithing_table",
                    "minecraft:anvil",
                    "minecraft:grindstone",
                    "minecraft:enchanting_table",
                    "minecraft:furnace",
                    "minecraft:blast_furnace",
                    "minecraft:smoker",
                    "minecraft:brewing_stand",
                    "minecraft:hopper",
                    "minecraft:barrel",
                    "minecraft:chest",
                    "minecraft:trapped_chest",
                    "minecraft:shulker_box",
                    "minecraft:beacon",
                    "minecraft:dispenser",
                    "minecraft:dropper",
                    "minecraft:crafter",
                    "minecraft:lectern",
                    "minecraft:flower_pot",
                    "minecraft:note_block",
                    "minecraft:decorated_pot",
                    "minecraft:light" -> true;
            default -> false;
        };
    }

    static @Nullable InteractionResult playerItemUse(
            BlockState state,
            Level level,
            BlockPos pos,
            PlacementSnapshot snapshot
    ) {
        // RespawnAnchorBlock.charge mutates the shared Level on both client and
        // server. Keep this on the detached client path so the runtime fork's
        // ServerLevel-only behavior is never reached during prediction.
        if (state.getBlock() instanceof RespawnAnchorBlock
                && snapshot.getItemStack().getItem() == net.minecraft.world.item.Items.GLOWSTONE
                && state.getValue(RespawnAnchorBlock.CHARGE) < RespawnAnchorBlock.MAX_CHARGES) {
            level.setBlock(pos, state.setValue(
                    RespawnAnchorBlock.CHARGE,
                    state.getValue(RespawnAnchorBlock.CHARGE) + 1
            ), 3);
            return InteractionResult.SUCCESS;
        }
        return null;
    }

    static @Nullable InteractionResult playerFreeEmptyHandUse(
            BlockState state,
            Level level,
            BlockHitResult hitResult
    ) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            // DoorBlock.useWithoutItem is server-fork code on the runtime side. Leaf
            // and Purpur add their redstone configuration check there, but the client
            // prediction path only checks BlockSetType#canOpenByHand and cycles OPEN.
            if (!canOpenByHand(door)) {
                return null;
            }
            level.setBlock(hitResult.getBlockPos(), state.cycle(DoorBlock.OPEN), 10);
            return InteractionResult.SUCCESS;
        }
        if (block instanceof TrapDoorBlock trapDoor) {
            // Match MCP-Reborn TrapDoorBlock.useWithoutItem without invoking the
            // server implementation (which may consult fork-owned Level state).
            if (!canOpenByHand(trapDoor)) {
                return null;
            }
            level.setBlock(hitResult.getBlockPos(), state.cycle(TrapDoorBlock.OPEN), 2);
            return InteractionResult.SUCCESS;
        }
        if (block instanceof LeverBlock) {
            // LeverBlock.useWithoutItem changes only particles on the client. The
            // authoritative pull and block-state change happen on the server.
            return InteractionResult.SUCCESS;
        }
        return null;
    }

    private static boolean canOpenByHand(Block block) {
        try {
            Object type = invokeNoArg(block, "type");
            return Boolean.TRUE.equals(invokeNoArg(type, "canOpenByHand"));
        } catch (ReflectiveOperationException ignored) {
            // 1.21.3 exposes the same data through the protected trapdoor accessor
            // rather than the public DoorBlock#type method. Keep this fallback
            // independent of the runtime fork's Level implementation.
            try {
                Object type = invokeNoArg(block, "getType");
                return Boolean.TRUE.equals(invokeNoArg(type, "canOpenByHand"));
            } catch (ReflectiveOperationException fallbackFailure) {
                return block instanceof DoorBlock door && DoorBlock.isWoodenDoor(door.defaultBlockState());
            }
        }
    }

    private static Object invokeNoArg(Object receiver, String name) throws ReflectiveOperationException {
        if (receiver == null) {
            throw new NoSuchMethodException(name);
        }
        for (Class<?> type = receiver.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(receiver);
            } catch (NoSuchMethodException ignored) {
                // Continue through the NMS inheritance hierarchy.
            }
        }
        throw new NoSuchMethodException(receiver.getClass().getName() + "#" + name);
    }

    static @Nullable InteractionResult playerDependentClientNoop(BlockState state, PlacementSnapshot snapshot) {
        if (state.getBlock() instanceof EnderChestBlock) {
            return InteractionResult.SUCCESS;
        }
        if (state.getBlock() instanceof DaylightDetectorBlock
                && snapshot.getGameMode() != org.bukkit.GameMode.ADVENTURE
                && snapshot.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            return InteractionResult.SUCCESS;
        }
        return null;
    }
}
