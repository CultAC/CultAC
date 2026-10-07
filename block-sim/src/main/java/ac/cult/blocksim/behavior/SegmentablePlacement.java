package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;

final class SegmentablePlacement {
    private SegmentablePlacement() { }

    static boolean canBeReplaced(SimLevel level, int state, PlacementContext context, String segment) {
        return !context.secondaryUseActive() && context.stack().is(level.registry().block(state).bindings().get("asItem"))
            && Integer.parseInt(level.registry().value(state, segment)) < 4;
    }

    static int placementState(BlockDefinition block, PlacementContext context, String segment) {
        var registry = context.level().registry(); int old = context.level().stateAt(context.clickedPos());
        return registry.block(old) == block ? registry.with(old, segment, Integer.toString(Math.min(4, Integer.parseInt(registry.value(old, segment)) + 1)))
            : registry.with(block.defaultState(), "facing", context.horizontalDirection().opposite().name().toLowerCase(Locale.ROOT));
    }
}
