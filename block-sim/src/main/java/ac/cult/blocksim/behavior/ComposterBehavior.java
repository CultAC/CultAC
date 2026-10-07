package ac.cult.blocksim.behavior;

import ac.cult.blocksim.interaction.*;

public final class ComposterBehavior extends BlockBehavior {
    @Override public SimInteraction useItemOn(int state, UseContext context) {
        return number(context.level(), state, "level") < 8 && context.stack().components().has("minecraft:compostable")
            ? SimInteraction.SUCCESS : SimInteraction.TRY_WITH_EMPTY_HAND;
    }
    @Override public SimInteraction useWithoutItem(int state, UseContext context) {
        if (number(context.level(), state, "level") != 8) return SimInteraction.PASS;
        context.level().setBlock(context.clickedPos(), context.level().registry().with(state, "level", "0"), 3);
        return SimInteraction.SUCCESS;
    }
}
