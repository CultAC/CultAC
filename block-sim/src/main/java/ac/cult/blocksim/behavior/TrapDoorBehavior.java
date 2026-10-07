package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Signals;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Locale;

public final class TrapDoorBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); var pos = context.clickedPos(); var face = context.clickedFace(); int state = block.defaultState();
        if (!context.replacingClicked() && face.horizontal()) {
            state = level.registry().with(state, "facing", face.name().toLowerCase(Locale.ROOT));
            state = level.registry().with(state, "half", context.clickLocation().y() - pos.y() > 0.5 ? "top" : "bottom");
        } else {
            state = level.registry().with(state, "facing", context.horizontalDirection().opposite().name().toLowerCase(Locale.ROOT));
            state = level.registry().with(state, "half", face == Direction.UP ? "bottom" : "top");
        }
        if (new Signals(level).hasNeighborSignal(pos)) state = level.registry().with(level.registry().with(state, "open", "true"), "powered", "true");
        return level.registry().with(state, "waterlogged", Boolean.toString(level.fluidAt(pos).is("minecraft:water")));
    }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        if (!context.level().registry().block(state).bindings().get("TrapDoorBlock.type").endsWith(":canOpenByHand=true")) return SimInteraction.PASS;
        toggle(context.level(), state, context.clickedPos());
        return SimInteraction.SUCCESS;
    }

    private void toggle(SimLevel level, int state, BlockPos pos) {
        level.setBlock(pos, level.registry().with(state, "open", Boolean.toString(!bool(level, state, "open"))), 2);
        // Water scheduling, sound, and game events do not change the prediction outputs.
    }

}
