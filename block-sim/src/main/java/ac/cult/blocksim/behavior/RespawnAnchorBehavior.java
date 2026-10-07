package ac.cult.blocksim.behavior;

import ac.cult.blocksim.interaction.*;

/** Charge writes and fuel consumption are local; setting spawn and explosions are server-only. */
public final class RespawnAnchorBehavior extends BlockBehavior {
    @Override public SimInteraction useItemOn(int state, UseContext context) {
        boolean canCharge = number(context.level(), state, "charges") < 4;
        if (canCharge && context.stack().is("minecraft:glowstone")) {
            context.level().setBlock(context.clickedPos(), context.level().registry().with(state, "charges", Integer.toString(number(context.level(), state, "charges") + 1)), 3);
            context.stack().consume(1, context.player());
            return SimInteraction.SUCCESS;
        }
        return context.hand() == Hand.MAIN_HAND && canCharge && context.player().hand(Hand.OFF_HAND).is("minecraft:glowstone") ? SimInteraction.PASS : SimInteraction.TRY_WITH_EMPTY_HAND;
    }
    @Override public SimInteraction useWithoutItem(int state, UseContext context) {
        return number(context.level(), state, "charges") == 0 ? SimInteraction.PASS : SimInteraction.CONSUME;
    }
}
