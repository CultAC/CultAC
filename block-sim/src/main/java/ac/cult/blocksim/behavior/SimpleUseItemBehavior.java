package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** Small client branches whose only effects are stack consumption or starting local item use. */
public final class SimpleUseItemBehavior implements ItemBehavior {
    public enum Kind { THROW, ACKNOWLEDGE, PASS, BUNDLE, SPYGLASS, KNOWLEDGE_BOOK }
    private final ItemBehavior defaults;
    private final Kind kind;
    public SimpleUseItemBehavior(ItemBehavior defaults, Kind kind) { this.defaults = defaults; this.kind = kind; }
    @Override public SimInteraction useOn(UseContext context) { return defaults.useOn(context); }
    @Override public SimInteraction use(UseContext context) {
        return switch (kind) {
            case PASS -> SimInteraction.PASS;
            case ACKNOWLEDGE -> SimInteraction.SUCCESS;
            case THROW -> { context.stack().consume(1, context.player()); yield SimInteraction.SUCCESS; }
            case BUNDLE, SPYGLASS -> {
                context.player().startUsingItem(context.hand(), useDuration(context.stack(), context.player()));
                yield kind == Kind.BUNDLE ? SimInteraction.SUCCESS : SimInteraction.CONSUME;
            }
            case KNOWLEDGE_BOOK -> {
                var recipes = context.stack().components().get("minecraft:recipes");
                context.stack().consume(1, context.player());
                yield recipes == null || recipes.getAsJsonArray().isEmpty() ? SimInteraction.FAIL : SimInteraction.SUCCESS;
            }
        };
    }
    @Override public int useDuration(SimItemStack stack, SimPlayer player) {
        return kind == Kind.BUNDLE ? 200 : kind == Kind.SPYGLASS ? 1200 : ItemBehavior.super.useDuration(stack, player);
    }
}
