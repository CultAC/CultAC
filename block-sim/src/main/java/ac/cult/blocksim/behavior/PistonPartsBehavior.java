package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;

/** Head support and the moving part's ordered base removal share one piston mechanic. */
public final class PistonPartsBehavior extends BlockBehavior {
    public enum Part { HEAD, MOVING }
    private final Part part;
    public PistonPartsBehavior(Part part) { this.part = part; }

    @Override
    public ac.cult.blocksim.engine.shapes.VoxelShape collisionShape(SimLevel level, int state, BlockPos pos) {
        return part == Part.MOVING ? level.movingPistonCollisionAt(pos) : super.collisionShape(level, state, pos);
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        if (part == Part.MOVING) return true;
        var direction = facing(level, state);
        int base = level.stateAt(pos.relative(direction.opposite()));
        String key = level.registry().block(base).key();
        String required = level.registry().value(state, "type").equals("sticky") ? "minecraft:sticky_piston" : "minecraft:piston";
        return (key.equals(required) && bool(level, base, "extended") || key.equals("minecraft:moving_piston"))
            && facing(level, base) == direction;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return part == Part.HEAD && direction.opposite() == facing(level, state) && !canSurvive(level, state, pos)
            ? level.registry().block("minecraft:air").defaultState() : state;
    }

    @Override
    public void destroy(SimLevel level, int state, BlockPos pos) {
        if (part == Part.HEAD) return;
        var basePos = pos.relative(facing(level, state).opposite());
        int base = level.stateAt(basePos);
        if (isFamily(level, base, "piston.PistonBaseBlock") && bool(level, base, "extended"))
            level.setBlock(basePos, level.fluidAt(basePos).createLegacyBlock(), 3);
    }
}
