package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;

public final class BedBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level();
        Direction facing = context.horizontalDirection();
        BlockPos head = context.clickedPos().relative(facing);
        int existing = level.stateAt(head);
        return level.behavior(existing).canBeReplaced(level, existing, context) && level.isWithinBorder(head)
            ? level.registry().with(block.defaultState(), "facing", facing.name().toLowerCase(java.util.Locale.ROOT)) : -1;
    }
    private static Direction neighborDirection(SimLevel level, int state) {
        return level.registry().value(state, "part").equals("foot") ? facing(level, state) : facing(level, state).opposite();
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        if (direction != neighborDirection(level, state)) return super.updateShape(level, state, pos, direction, neighborPos, neighborState);
        return level.registry().sameBlock(state, neighborState) && !level.registry().value(state, "part").equals(level.registry().value(neighborState, "part"))
            ? level.registry().with(state, "occupied", level.registry().value(neighborState, "occupied")) : level.registry().block("minecraft:air").defaultState();
    }

    @Override
    public void setPlacedBy(SimLevel level, int state, BlockPos pos, SimPlayer player, SimItemStack stack) {
        // LevelWriter.setBlockAndUpdate writes with flags 3 on both sides.
        super.setPlacedBy(level, state, pos, player, stack);
        level.setBlock(pos.relative(facing(level, state)), level.registry().with(state, "part", "head"), 3);
    }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) { return SimInteraction.SUCCESS_SERVER; }
}
