package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.interaction.ItemResults;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;

/** BottleItem's client water pickup. Cloud owner references are not synchronized to clients. */
public final class BottleItemBehavior implements ItemBehavior {
    private final ItemBehavior defaults;
    private final ItemRegistry items;
    private final Set<String> water;
    public BottleItemBehavior(DataTables data, ItemBehavior defaults, ItemRegistry items) {
        this.defaults = defaults; this.items = items;
        water = data.tags().get("fluid:minecraft:water");
    }
    @Override public SimInteraction useOn(UseContext context) { return defaults.useOn(context); }
    @Override public SimInteraction use(UseContext context) {
        var pick = BlockRaycast.playerPick(context.level(), context.player(), BlockRaycast.Fluid.SOURCE_ONLY);
        if (pick.miss() || !water.contains(context.level().fluidAt(pick.hit().pos()).type())) return SimInteraction.PASS;
        var potion = items.stack("minecraft:potion", 1);
        var contents = new JsonObject(); contents.addProperty("potion", "minecraft:water");
        potion.components(potion.components().with("minecraft:potion_contents", contents,
            new NbtValue.Compound(Map.of("potion", new NbtValue.Text("minecraft:water")))));
        return SimInteraction.SUCCESS.transformedTo(ItemResults.filled(context.stack(), context.player(), potion));
    }
}
