package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

/** InstrumentItem's immediate local use and cooldown; durability changes require ServerLevel. */
public final class InstrumentItemBehavior implements ItemBehavior {
    private final ItemBehavior defaults;
    private final InteractionRegistries registries;
    public InstrumentItemBehavior(ItemBehavior defaults, InteractionRegistries registries) { this.defaults = defaults; this.registries = registries; }
    @Override public SimInteraction useOn(UseContext context) { return defaults.useOn(context); }
    @Override public SimInteraction use(UseContext context) {
        if (!context.stack().components().has("minecraft:instrument")) return SimInteraction.FAIL;
        int duration = useDuration(context.stack(), context.player());
        context.player().startUsingItem(context.hand(), duration);
        var instrument = registries.resolve("instrument", context.stack().components().get("minecraft:instrument")).getAsJsonObject();
        if (instrument.get("use_duration").getAsFloat() > 0) context.cooldowns().add(context.stack(), duration);
        return SimInteraction.CONSUME;
    }
    @Override public int useDuration(SimItemStack stack, SimPlayer player) {
        var component = stack.components().get("minecraft:instrument");
        return component == null ? 0 : (int) Math.floor(registries.resolve("instrument", component).getAsJsonObject().get("use_duration").getAsFloat() * 20.0F);
    }
}
