package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Signals;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Set;

public final class FenceGateBehavior extends BlockBehavior {
    private final Set<String> walls;
    public FenceGateBehavior(Set<String> walls) { this.walls = Set.copyOf(walls); }

    private boolean isWall(SimLevel level, int state) { return walls.contains(level.registry().block(state).key()); }

    static boolean connectsToDirection(SimLevel level, int state, Direction direction) {
        return facing(level, state).axisName().equals(direction.clockwise().axisName());
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        if (!facing(level, state).clockwise().axisName().equals(direction.axisName())) return super.updateShape(level, state, pos, direction, neighborPos, neighborState);
        boolean inWall = isWall(level, neighborState) || isWall(level, level.stateAt(pos.relative(direction.opposite())));
        return level.registry().with(state, "in_wall", Boolean.toString(inWall));
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos(); Direction direction = context.horizontalDirection();
        boolean open = new Signals(level).hasNeighborSignal(pos);
        boolean inWall = direction.axisName().equals("z") && (isWall(level, level.stateAt(pos.relative(Direction.WEST))) || isWall(level, level.stateAt(pos.relative(Direction.EAST))))
            || direction.axisName().equals("x") && (isWall(level, level.stateAt(pos.relative(Direction.NORTH))) || isWall(level, level.stateAt(pos.relative(Direction.SOUTH))));
        int result = level.registry().with(block.defaultState(), "facing", direction.name().toLowerCase(java.util.Locale.ROOT));
        result = level.registry().with(result, "open", Boolean.toString(open));
        result = level.registry().with(result, "powered", Boolean.toString(open));
        return level.registry().with(result, "in_wall", Boolean.toString(inWall));
    }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        SimLevel level = context.level();
        if (bool(level, state, "open")) {
            state = level.registry().with(state, "open", "false");
        } else {
            Direction direction = context.horizontalDirection();
            if (facing(level, state) == direction.opposite()) state = level.registry().with(state, "facing", direction.name().toLowerCase(java.util.Locale.ROOT));
            state = level.registry().with(state, "open", "true");
        }
        level.setBlock(context.clickedPos(), state, 10);
        // Sound and game-event calls do not mutate the contract's outputs.
        return SimInteraction.SUCCESS;
    }
}
