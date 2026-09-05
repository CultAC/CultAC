package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

import java.util.List;

public final class NativeBlockCollisionHelper {
    private NativeBlockCollisionHelper() {
    }

    public static CollisionBox getCollisionBox(CultPlayer player, BlockState state, int x, int y, int z) {
        return getCollisionBox(player, state, x, y, z, player == null ? Double.NaN : player.y);
    }

    public static CollisionBox getCollisionBox(CultPlayer player, BlockState state, int x, int y, int z, double entityBottom) {
        return getCollisionBox(player, state, x, y, z, entityBottom, JavaCollisionState.current(player));
    }

    public static CollisionBox getCollisionBox(CultPlayer player, BlockState state, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        if (player == null || player.compensatedWorld == null || state == null || state.isAir()) {
            return NoCollisionBox.INSTANCE;
        }

        return fromShape(getCollisionShape(player, state, x, y, z, entityBottom, actor), x, y, z);
    }

    public static CollisionBox getSelectionBox(CultPlayer player, BlockState state, int x, int y, int z) {
        if (player == null || player.compensatedWorld == null || state == null || state.isAir()) {
            return NoCollisionBox.INSTANCE;
        }

        return fromShape(getSelectionShape(player, state, x, y, z), x, y, z);
    }

    public static CollisionBox getCollisionBox(CultPlayer player, BlockData data, int x, int y, int z) {
        return getCollisionBox(player, data, x, y, z, player == null ? Double.NaN : player.y);
    }

    public static CollisionBox getCollisionBox(CultPlayer player, BlockData data, int x, int y, int z, double entityBottom) {
        return getCollisionBox(player, data, x, y, z, entityBottom, JavaCollisionState.current(player));
    }

    public static CollisionBox getCollisionBox(CultPlayer player, BlockData data, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        if (player == null || data == null) {
            return NoCollisionBox.INSTANCE;
        }
        if (data instanceof CraftBlockData craftBlockData) {
            return getCollisionBox(player, craftBlockData.getState(), x, y, z, entityBottom, actor);
        }
        return getCollisionBox(player, player.compensatedWorld.getBlockStateAt(x, y, z), x, y, z, entityBottom, actor);
    }

    public static VoxelShape getCollisionShape(CultPlayer player, BlockState state, int x, int y, int z) {
        return getCollisionShape(player, state, x, y, z, player == null ? Double.NaN : player.y);
    }

    public static VoxelShape getCollisionShape(CultPlayer player, BlockState state, int x, int y, int z, double entityBottom) {
        return getCollisionShape(player, state, x, y, z, entityBottom, JavaCollisionState.current(player));
    }

    public static VoxelShape getCollisionShape(CultPlayer player, BlockState state, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        if (player == null || player.compensatedWorld == null || state == null || state.isAir()) {
            return net.minecraft.world.phys.shapes.Shapes.empty();
        }

        if (state.getBlock() == Blocks.POWDER_SNOW && actor != null) {
            return actor.powderSnowShape(y, entityBottom);
        }

        if (state.getBlock() == Blocks.MOVING_PISTON) {
            return player.compensatedWorld.pistons.getMovingPistonCollisionShape(new BlockPos(x, y, z));
        }

        return state.getCollisionShape(player.compensatedWorld, new BlockPos(x, y, z), collisionContext(player, entityBottom));
    }

    public static VoxelShape getSelectionShape(CultPlayer player, BlockState state, int x, int y, int z) {
        if (player == null || player.compensatedWorld == null || state == null || state.isAir()) {
            return net.minecraft.world.phys.shapes.Shapes.empty();
        }

        return state.getShape(player.compensatedWorld, new BlockPos(x, y, z), collisionContext(player));
    }

    public static List<AABB> toWorldAabbs(VoxelShape shape, int x, int y, int z) {
        if (shape == null || shape.isEmpty()) {
            return List.of();
        }

        return shape.toAabbs().stream()
                .map(box -> box.move(x, y, z))
                .toList();
    }

    public static CollisionBox fromShape(VoxelShape shape, int x, int y, int z) {
        if (shape == null || shape.isEmpty()) {
            return NoCollisionBox.INSTANCE;
        }

        List<AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty()) {
            return NoCollisionBox.INSTANCE;
        }

        if (boxes.size() == 1) {
            return fromAabb(boxes.get(0), x, y, z);
        }

        SimpleCollisionBox[] collisionBoxes = new SimpleCollisionBox[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            collisionBoxes[i] = fromAabb(boxes.get(i), x, y, z);
        }
        return new ComplexCollisionBox(collisionBoxes);
    }

    private static SimpleCollisionBox fromAabb(AABB box, int x, int y, int z) {
        return new SimpleCollisionBox(
                box.minX + x,
                box.minY + y,
                box.minZ + z,
                box.maxX + x,
                box.maxY + y,
                box.maxZ + z);
    }

    public static CollisionContext collisionContext(CultPlayer player) {
        return collisionContext(player, player == null ? Double.NaN : player.y);
    }

    public static CollisionContext collisionContext(CultPlayer player, double entityBottom) {
        if (player == null) {
            return CollisionContext.empty();
        }
        return new CultCollisionContext(player, entityBottom);
    }

    private static final class CultCollisionContext implements CollisionContext {
        private final boolean descending;
        private final double entityBottom;
        private final net.minecraft.world.item.ItemStack heldItem;

        private CultCollisionContext(CultPlayer player, double entityBottom) {
            this.descending = player.isSneaking;
            this.entityBottom = entityBottom;
            this.heldItem = heldItem(player);
        }

        private static net.minecraft.world.item.ItemStack heldItem(CultPlayer player) {
            try {
                return SpigotConversionUtil.toNmsItemStack(player.getInventory().getHeldItem());
            } catch (RuntimeException ignored) {
                return net.minecraft.world.item.ItemStack.EMPTY;
            }
        }

        @Override
        public boolean isDescending() {
            return descending;
        }

        @Override
        public boolean isAbove(VoxelShape shape, BlockPos pos, boolean defaultValue) {
            return entityBottom > pos.getY() + shape.max(Direction.Axis.Y) - 1.0E-5F;
        }

        @Override
        public boolean isHoldingItem(Item item) {
            return heldItem.is(item);
        }

        @Override
        public boolean alwaysCollideWithFluid() {
            return false;
        }

        @Override
        public boolean canStandOnFluid(FluidState fluidStateAbove, FluidState fluid) {
            return false;
        }

        @Override
        public VoxelShape getCollisionShape(BlockState state, CollisionGetter collisionGetter, BlockPos pos) {
            return state.getCollisionShape(collisionGetter, pos, this);
        }
    }
}
