package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.engine.shapes.*;
import ac.cult.blocksim.interaction.*;

public final class ShulkerBoxBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) { return context.level().registry().with(block.defaultState(), "facing", context.clickedFace().name().toLowerCase(java.util.Locale.ROOT)); }
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) { return SimInteraction.SUCCESS; }
    @Override
    public VoxelShape outlineShape(SimLevel level, int state, BlockPos pos, EntityCollisionContext context) { return collisionShape(level, state, pos); }
    @Override
    public VoxelShape collisionShape(SimLevel level, int state, BlockPos pos) {
        var entity = level.blockEntityAt(pos);
        if (entity == null || !entity.type().equals("minecraft:shulker_box")) return Shapes.block();
        var progress = entity.data().get("progress");
        float lid = 0.5F * (progress == null ? 0.0F : progress.getAsFloat());
        // ShulkerBoxBlockEntity.getBoundingBox -> Shulker.getProgressAabb(1, facing, lid, bottomCenter).
        return Shapes.create(ShulkerGeometry.progressBounds(1.0F, facing(level, state), lid, new Vec3(0.5, 0, 0.5)));
    }

    @Override
    public VoxelShape supportShape(SimLevel level, int state, BlockPos pos) {
        var entity = level.blockEntityAt(pos);
        var animation = entity == null ? null : entity.data().get("animation_status");
        if (entity == null || !entity.type().equals("minecraft:shulker_box") || animation == null || animation.getAsString().equals("CLOSED")) return Shapes.block();
        // rotateAll of the north 1/16 support slab, indexed by facing.opposite().
        return Shapes.create(switch (facing(level, state).opposite()) {
            case DOWN -> new Box(0, 0, 0, 1, 1.0 / 16, 1);
            case UP -> new Box(0, 15.0 / 16, 0, 1, 1, 1);
            case NORTH -> new Box(0, 0, 0, 1, 1, 1.0 / 16);
            case SOUTH -> new Box(0, 0, 15.0 / 16, 1, 1, 1);
            case WEST -> new Box(0, 0, 0, 1.0 / 16, 1, 1);
            case EAST -> new Box(15.0 / 16, 0, 0, 1, 1, 1);
        });
    }

    @Override public boolean isRedstoneConductor(SimLevel level, int state, BlockPos pos) {
        // Blocks.shulkerBoxProperties uses Properties' isCollisionShapeFullBlock predicate.
        return level.collisionShapeFullBlock(state, pos);
    }
}
