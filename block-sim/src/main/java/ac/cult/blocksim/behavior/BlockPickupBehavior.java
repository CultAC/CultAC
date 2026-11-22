package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;

/** Liquid sources, bubble columns and powder snow share the client pickup write. */
public class BlockPickupBehavior extends BlockBehavior {
    private final String bucket;
    private final boolean sourceOnly;
    public BlockPickupBehavior(String bucket, boolean sourceOnly) { this.bucket = bucket; this.sourceOnly = sourceOnly; }
    @Override
    public VoxelShape collisionShape(SimLevel level, int state, BlockPos pos, EntityCollisionContext context) {
        // LiquidBlock alwaysCollideWithFluid is independent of its source/flowing level.
        if (sourceOnly && context.alwaysCollideWithFluid()) return Shapes.block();
        if (!"minecraft:powder_snow_bucket".equals(bucket)) return super.collisionShape(level, state, pos, context);
        if (context.placement()) return Shapes.empty();
        if (context.fallDistance() > 2.5) return Shapes.create(0, 0, 0, 1, .9F, 1);
        return context.fallingBlock() || context.powderSnowWalkable() && context.isAbove(1, pos) && !context.descending()
            ? Shapes.block() : Shapes.empty();
    }
    @Override
    public String pickupBlock(SimLevel level, int state, BlockPos pos, boolean creativePlayer) {
        if (sourceOnly && number(level, state, "level") != 0) return null;
        level.setBlock(pos, level.registry().block("minecraft:air").defaultState(), 11);
        return bucket == null ? level.registry().block(state).bindings().get("fluidBucket") : bucket;
    }
}
