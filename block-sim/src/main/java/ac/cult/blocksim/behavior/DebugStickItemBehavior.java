package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** MCP 26.3 DebugStickItem: handleInteraction is ServerPlayer-only; the client still consumes useOn. */
public final class DebugStickItemBehavior implements ItemBehavior {
    private final ItemBehavior general;
    public DebugStickItemBehavior(ItemBehavior general) { this.general = general; }
    @Override public SimInteraction useOn(UseContext context) { return SimInteraction.SUCCESS; }
    @Override public SimInteraction use(UseContext context) { return general.use(context); }
    @Override public boolean canDestroyBlock(SimItemStack stack, SimPlayer player) { return false; }
}
