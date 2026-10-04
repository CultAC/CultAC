package ac.cult.vanilla.interaction;

import ac.cult.placement.api.InteractionEngine;
import java.util.HashMap;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** MultiPlayerGameMode's 26.3 action order, executing original vanilla item/block methods. */
public final class VanillaInteractions implements InteractionEngine, ac.cult.placement.api.PlacementEngine {
    private final InteractionBindings bindings;

    /** {@code narrowed}: see {@link InteractionBindings#MODEL_REGISTRIES} and {@code ModelCompaction}. */
    public VanillaInteractions(boolean narrowed) {
        bindings = new InteractionBindings(narrowed);
        // Link the normal client player/level path at startup, before the first packet action.
        var inventory = java.util.Collections.nCopies(43, Stack.EMPTY);
        var actor = new Actor(0, 64, 0, 0, 0, "STANDING", "SURVIVAL", false, 20, false, 0, inventory, false, 1, 4.5);
        var empty = new World() {
            public int stateAt(int x, int y, int z) {
                return 0;
            }

            public int minY() {
                return -64;
            }

            public int height() {
                return 384;
            }

            public boolean loaded(int x, int z) {
                return false;
            }
        };
        var request = new InteractionEngine.Request(
                Operation.USE,
                empty,
                actor,
                "MAIN_HAND",
                new Pos(0, 64, 0),
                "UP",
                0,
                64,
                0,
                false,
                "minecraft:overworld",
                null);
        new InteractionPlayer(new InteractionWorld(request, bindings.registries), actor);
    }

    @Override
    public InteractionEngine.Result interact(InteractionEngine.Request request) {
        return ac.cult.placement.runtime.RequestTags.query(request.world().tags(), () -> interactWithTags(request));
    }

    private InteractionEngine.Result interactWithTags(InteractionEngine.Request request) {
        var registries = bindings.registries;
        var world = new InteractionWorld(request, registries);
        var player = new InteractionPlayer(world, request.actor());
        var inventoryBefore = new ItemStack[request.actor().inventory().size()];
        for (int slot = 0; slot < inventoryBefore.length; slot++)
            inventoryBefore[slot] = player.getInventory().getItem(slot).copy();
        var hand = InteractionHand.valueOf(request.hand());
        var pos = new BlockPos(
                request.clicked().x(), request.clicked().y(), request.clicked().z());
        var hit = new BlockHitResult(
                new Vec3(request.hitX(), request.hitY(), request.hitZ()),
                Direction.valueOf(request.face()),
                pos,
                request.inside());
        if (request.actor().cooldown()) player.getCooldowns().addCooldown(player.getItemInHand(hand), 1);
        player.cooldowns().clear();
        boolean consumes;
        boolean success;
        if (request.operation() == Operation.BREAK) {
            success = destroyBlock(world, player, pos);
            consumes = success;
        } else {
            var result = request.operation() == Operation.USE_ON
                    ? useOn(world, player, hand, hit)
                    : use(world, player, hand);
            consumes = result.consumesAction();
            success = result != InteractionResult.FAIL;
        }
        var changes = new HashMap<Integer, Stack>();
        for (int slot = 0; slot < request.actor().inventory().size(); slot++) {
            var after = player.getInventory().getItem(slot);
            if (!ItemStack.matches(inventoryBefore[slot], after))
                changes.put(slot, InteractionItems.encode(after, registries));
        }
        return new InteractionEngine.Result(
                consumes,
                success,
                world.writes,
                changes,
                player.cooldowns(),
                player.isUsingItem(),
                player.isUsingItem() ? player.getUsedItemHand().name() : hand.name(),
                player.getFoodData().getFoodLevel());
    }

    private static InteractionResult useOn(
            InteractionWorld world, InteractionPlayer player, InteractionHand hand, BlockHitResult hit) {
        if (!world.getWorldBorder().isWithinBounds(hit.getBlockPos())) return InteractionResult.FAIL;
        if (player.gameMode() == GameType.SPECTATOR) return InteractionResult.CONSUME;
        ItemStack stack = player.getItemInHand(hand);
        boolean haveItems =
                !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
        if (!(player.isSecondaryUseActive() && haveItems)) {
            var state = world.getBlockState(hit.getBlockPos());
            if (!state.getBlock().isEnabled(world.enabledFeatures())) return InteractionResult.FAIL;
            var result = state.useItemOn(stack, world, player, hand, hit);
            if (result.consumesAction()) return result;
            if (result instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND) {
                result = state.useWithoutItem(world, player, hit);
                if (result.consumesAction()) return result;
            }
        }
        if (stack.isEmpty() || player.getCooldowns().isOnCooldown(stack)) return InteractionResult.PASS;
        var context = new UseOnContext(player, hand, hit);
        if (player.hasInfiniteMaterials()) {
            int count = stack.getCount();
            var result = stack.useOn(context);
            stack.setCount(count);
            return result;
        }
        var result = stack.useOn(context);
        ModelBootstrap.afterUseOn(player, hand, stack, result);
        return result;
    }

    private static InteractionResult use(InteractionWorld world, InteractionPlayer player, InteractionHand hand) {
        if (player.gameMode() == GameType.SPECTATOR) return InteractionResult.PASS;
        var stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(stack)) return InteractionResult.PASS;
        var result = stack.use(world, player, hand);
        var after = result instanceof InteractionResult.Success success
                ? Objects.requireNonNullElseGet(success.heldItemTransformedTo(), () -> player.getItemInHand(hand))
                : player.getItemInHand(hand);
        if (after != stack) player.setItemInHand(hand, after);
        return result;
    }

    private static boolean destroyBlock(InteractionWorld world, InteractionPlayer player, BlockPos pos) {
        if (player.blockActionRestricted(world, pos, player.gameMode())) return false;
        var state = world.getBlockState(pos);
        if (!player.getMainHandItem().canDestroyBlock(state, world, pos, player)) return false;
        var block = state.getBlock();
        if (block instanceof GameMasterBlock && !player.canUseGameMasterBlocks() || state.isAir()) return false;
        block.playerWillDestroy(world, pos, state, player);
        boolean changed = world.setBlock(pos, world.getFluidState(pos).createLegacyBlock(), 11);
        if (changed) block.destroy(world, pos, state);
        return changed;
    }

    @Override
    public int stateCount() {
        return net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size();
    }

    @Override
    public String stateName(int id) {
        return VanillaBlockGeometry.state(id).toString();
    }

    @Override
    public ac.cult.placement.api.BlockGeometry.State state(int id) {
        return VanillaBlockGeometry.describe(id);
    }

    @Override
    public java.util.List<Box> shape(
            World world,
            Pos pos,
            int id,
            ac.cult.placement.api.BlockGeometry.Shape kind,
            ac.cult.placement.api.BlockGeometry.Context actor) {
        return ac.cult.placement.runtime.RequestTags.query(
                world.tags(), () -> VanillaBlockGeometry.shape(world, pos, id, kind, actor));
    }

    @Override
    public java.util.List<Box> collision(World world, Pos pos) {
        return shape(
                world,
                pos,
                world.stateAt(pos.x(), pos.y(), pos.z()),
                ac.cult.placement.api.BlockGeometry.Shape.COLLISION,
                null);
    }

    @Override
    public java.util.List<Box> outline(World world, Pos pos) {
        return shape(
                world,
                pos,
                world.stateAt(pos.x(), pos.y(), pos.z()),
                ac.cult.placement.api.BlockGeometry.Shape.OUTLINE,
                null);
    }

    @Override
    public ac.cult.placement.api.PlacementEngine.Result place(ac.cult.placement.api.PlacementEngine.Request request) {
        var inventory = new java.util.ArrayList<>(java.util.Collections.nCopies(43, Stack.EMPTY));
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.Identifier.parse(request.item()))
                .orElseThrow();
        var stack = new ItemStack(item, request.count());
        if (!request.blockProperties().isEmpty())
            stack.set(
                    net.minecraft.core.component.DataComponents.BLOCK_STATE,
                    new net.minecraft.world.item.component.BlockItemStateProperties(request.blockProperties()));
        inventory.set(0, InteractionItems.encode(stack, bindings.registries));
        var actor = new Actor(
                request.playerX(),
                request.playerY(),
                request.playerZ(),
                request.yaw(),
                request.pitch(),
                "STANDING",
                request.creative() ? "CREATIVE" : "SURVIVAL",
                request.secondaryUse(),
                20,
                false,
                0,
                inventory,
                false,
                1,
                request.creative() ? 5 : 4.5);
        var result = interact(new InteractionEngine.Request(
                Operation.USE_ON,
                request.world(),
                actor,
                "MAIN_HAND",
                request.clicked(),
                request.face(),
                request.hitX(),
                request.hitY(),
                request.hitZ(),
                request.inside(),
                "minecraft:overworld",
                null));
        var remaining = result.inventory().getOrDefault(0, inventory.get(0)).count();
        Pos primary = result.writes().isEmpty()
                ? request.clicked()
                : result.writes().getFirst().pos();
        return new ac.cult.placement.api.PlacementEngine.Result(result.consumes(), remaining, primary, result.writes());
    }

    @Override
    public void close() {
        bindings.close();
        ac.cult.placement.runtime.RequestTags.clear();
        ac.cult.placement.runtime.ModelThreadLocal.clearAll();
    }
}
