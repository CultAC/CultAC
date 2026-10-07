package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** BrushItem.useOn: acknowledge every use, start brushing only when the view picks a block. */
public final class BrushItemBehavior implements ItemBehavior {
    private final ItemBehavior defaults;
    public BrushItemBehavior(ItemBehavior defaults) { this.defaults = defaults; }
    @Override public SimInteraction useOn(UseContext context) {
        if (context.player() != null && BlockRaycast.brushPicksBlock(context.level(), context.player()))
            context.player().startUsingItem(context.hand(), 200);
        return SimInteraction.CONSUME;
    }
    @Override public SimInteraction use(UseContext context) { return defaults.use(context); }
    @Override public int useDuration(SimItemStack stack, SimPlayer player) { return 200; }
}
