package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;

public final class CandleCakeBehavior extends BlockBehavior {
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        // dropResources after eating runs only on ServerLevel and adds no client writes.
        return CakeBehavior.eat(context.level(), context.clickedPos(), context.level().registry().block("minecraft:cake").defaultState(), context.player());
    }
    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        return level.registry().facts(level.stateAt(pos.relative(Direction.DOWN))).has(StateFacts.SOLID);
    }
    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction == Direction.DOWN && !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
    @Override
    public SimInteraction useItemOn(int state, UseContext context) {
        if (context.stack().is("minecraft:flint_and_steel") || context.stack().is("minecraft:fire_charge")) return SimInteraction.PASS;
        if (context.clickLocation().y() - context.clickedPos().y() > 0.5 && context.stack().isEmpty() && bool(context.level(), state, "lit")) {
            context.level().setBlock(context.clickedPos(), context.level().registry().with(state, "lit", "false"), 11);
            return SimInteraction.SUCCESS;
        }
        return super.useItemOn(state, context);
    }
}
