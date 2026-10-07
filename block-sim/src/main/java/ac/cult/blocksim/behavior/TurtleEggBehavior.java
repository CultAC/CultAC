package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;

public final class TurtleEggBehavior extends BlockBehavior {
    @Override
    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) {
        return !context.secondaryUseActive() && context.stack().is(level.registry().block(state).bindings().get("asItem")) && number(level, state, "eggs") < 4
            || super.canBeReplaced(level, state, context);
    }
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); int old = level.stateAt(context.clickedPos());
        return level.registry().block(old) == block ? level.registry().with(old, "eggs", Integer.toString(Math.min(4, number(level, old, "eggs") + 1))) : super.placementState(block, context);
    }
}
