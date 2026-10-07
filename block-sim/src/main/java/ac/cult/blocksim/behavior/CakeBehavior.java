package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import java.util.Map;
import java.util.Set;

public final class CakeBehavior extends BlockBehavior {
    static SimInteraction eat(SimLevel level, BlockPos pos, int state, SimPlayer player) {
        if (!player.canEat(false)) return SimInteraction.PASS;
        player.eat(2, 0.1F);
        int bites = number(level, state, "bites");
        if (bites < 6) level.setBlock(pos, level.registry().with(state, "bites", Integer.toString(bites + 1)), 3);
        else level.setBlock(pos, level.fluidAt(pos).createLegacyBlock(), 3);
        return SimInteraction.SUCCESS;
    }
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        if (eat(context.level(), context.clickedPos(), state, context.player()).consumesAction()) return SimInteraction.SUCCESS;
        if (context.player().hand(Hand.MAIN_HAND).isEmpty()) return SimInteraction.CONSUME;
        return eat(context.level(), context.clickedPos(), state, context.player());
    }
    private final Set<String> candles;
    private final Map<String, Integer> candleCakes;
    public CakeBehavior(Set<String> candles, Map<String, Integer> candleCakes) {
        this.candles = Set.copyOf(candles); this.candleCakes = Map.copyOf(candleCakes);
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
        Integer cake = candleCakes.get(context.stack().itemKey());
        if (candles.contains(context.stack().itemKey()) && number(context.level(), state, "bites") == 0 && cake != null) {
            context.stack().consume(1, context.player());
            context.level().setBlock(context.clickedPos(), cake, 3);
            return SimInteraction.SUCCESS;
        }
        return SimInteraction.TRY_WITH_EMPTY_HAND;
    }
}
