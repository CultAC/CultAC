package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Set;

public final class NoteBehavior extends BlockBehavior {
    private final Set<String> topInstruments;
    public NoteBehavior(Set<String> topInstruments) { this.topInstruments = Set.copyOf(topInstruments); }

    private int setInstrument(SimLevel level, BlockPos pos, int state) {
        var above = level.registry().block(level.stateAt(pos.relative(Direction.UP))).bindings();
        if (Boolean.parseBoolean(above.get("instrument.worksAboveNoteBlock"))) return level.registry().with(state, "instrument", above.get("instrument"));
        var below = level.registry().block(level.stateAt(pos.relative(Direction.DOWN))).bindings();
        return level.registry().with(state, "instrument", Boolean.parseBoolean(below.get("instrument.worksAboveNoteBlock")) ? "harp" : below.get("instrument"));
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) { return setInstrument(context.level(), context.clickedPos(), block.defaultState()); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return !direction.horizontal() ? setInstrument(level, pos, state) : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }

    @Override
    public SimInteraction useItemOn(int state, UseContext context) {
        return topInstruments.contains(context.stack().itemKey()) && context.clickedFace() == Direction.UP ? SimInteraction.PASS : super.useItemOn(state, context);
    }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        // Tuning, world writes and statistics run only on the server.
        return SimInteraction.SUCCESS;
    }
}
