package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** MCP 26.3 CompassItem.useOn: track a lodestone on the client, including creative and split stacks. */
public final class CompassItemBehavior implements ItemBehavior {
    private final ItemBehavior general;
    private final ItemRegistry items;
    public CompassItemBehavior(ItemBehavior general, ItemRegistry items) { this.general = general; this.items = items; }

    @Override public SimInteraction useOn(UseContext context) {
        var level = context.level();
        if (!level.registry().block(level.stateAt(context.clickedPos())).key().equals("minecraft:lodestone"))
            return general.useOn(context);
        var position = new JsonArray();
        position.add(context.clickedPos().x()); position.add(context.clickedPos().y()); position.add(context.clickedPos().z());
        var target = new JsonObject(); target.addProperty("dimension", level.dimensionKey()); target.add("pos", position);
        // LodestoneTracker.CODEC omits tracked=true, its default, in both JSON and NBT.
        var tracker = new JsonObject(); tracker.add("target", target);
        var stack = context.stack();
        if (!context.player().state().infiniteMaterials() && stack.count() == 1) {
            track(stack, tracker);
        } else {
            var prototype = items.defaults("minecraft:compass");
            var split = new SimItemStack(items.item("minecraft:compass"), 1, prototype, stack.patch(), () -> items.defaults("minecraft:compass"));
            stack.consume(1, context.player());
            track(split, tracker);
            context.player().inventory().add(split);
        }
        return SimInteraction.SUCCESS;
    }
    private static void track(SimItemStack stack, JsonObject tracker) {
        var target = tracker.getAsJsonObject("target");
        var coordinates = target.getAsJsonArray("pos");
        var encodedTarget = new NbtValue.Compound(java.util.Map.of(
            "dimension", new NbtValue.Text(target.get("dimension").getAsString()),
            "pos", new NbtValue.PrimitiveArray(NbtValue.Kind.INT_ARRAY,
                java.util.List.of(coordinates.get(0).getAsLong(), coordinates.get(1).getAsLong(), coordinates.get(2).getAsLong()))));
        var encoding = new NbtValue.Compound(java.util.Map.of("target", encodedTarget));
        stack.components(stack.components().with("minecraft:lodestone_tracker", tracker, encoding));
    }
    @Override public SimInteraction use(UseContext context) { return general.use(context); }
}
