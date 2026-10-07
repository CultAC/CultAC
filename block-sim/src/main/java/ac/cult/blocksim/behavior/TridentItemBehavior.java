package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** TridentItem.use starts charging only with enough durability and a legal wet-state/effect combination. */
public final class TridentItemBehavior implements ItemBehavior {
    private final ItemBehavior defaults;

    public TridentItemBehavior(ItemBehavior defaults) { this.defaults = defaults; }

    @Override public SimInteraction useOn(UseContext context) { return defaults.useOn(context); }

    @Override public SimInteraction use(UseContext context) {
        var stack = context.player().hand(context.hand());
        if (stack.nextDamageWillBreak()) return SimInteraction.FAIL;
        var facts = context.player().tridentUse();
        if (facts.strength(context.hand()) > 0.0F && !facts.inWaterOrRain()) return SimInteraction.FAIL;
        context.player().startUsingItem(context.hand(), useDuration(stack, context.player()));
        return SimInteraction.CONSUME;
    }

    @Override public int useDuration(SimItemStack stack, SimPlayer player) { return 72000; }
}
