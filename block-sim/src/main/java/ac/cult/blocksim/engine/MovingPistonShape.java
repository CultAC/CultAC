package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.function.IntFunction;

/** Collision geometry from compensated piston state; no ticks or world mutations. */
public final class MovingPistonShape {
    private MovingPistonShape() { }

    public static VoxelShape collision(int movedState, Direction facing, boolean extending, boolean source,
                                       float progress, Direction noClip, IntFunction<VoxelShape> shapes) {
        VoxelShape base = !extending && source && BlockFamilies.PISTON_BASE.test(movedState)
                ? shapes.apply(BlockProps.EXTENDED.with(movedState, true)) : Shapes.empty();
        Direction movement = extending ? facing : facing.opposite();
        if (progress < 1.0 && noClip == movement) return base;
        int moving = source ? BlockProps.SHORT.with(
                BlockProps.FACING.with(BlockIds.PISTON_HEAD.defaultState(), facing.ordinal()),
                extending != (1.0F - progress < 0.25F)) : movedState;
        float offset = extending ? progress - 1.0F : 1.0F - progress;
        return Shapes.join(base, shapes.apply(moving).move(
                facing.x() * offset, facing.y() * offset, facing.z() * offset), BooleanOp.OR);
    }
}
