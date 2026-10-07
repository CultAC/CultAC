package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.interaction.ItemResults;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Set;

/** MCP 26.3 PotionItem.useOn and PotionContents.is: only a plain water potion converts mud. */
public final class PotionItemBehavior implements ItemBehavior {
    private final ItemBehavior general;
    private final ItemRegistry items;
    private final Set<String> convertible;
    public PotionItemBehavior(DataTables data, ItemBehavior general, ItemRegistry items) {
        this.general = general; this.items = items;
        convertible = data.tags().get("block:minecraft:convertible_to_mud");
    }
    @Override public SimInteraction useOn(UseContext context) {
        var level = context.level();
        var contents = context.stack().components().get("minecraft:potion_contents");
        if (context.hit().face() == Direction.DOWN || contents == null || !contents.isJsonObject()) return SimInteraction.PASS;
        var potion = contents.getAsJsonObject();
        if (!potion.has("potion") || !ac.cult.blocksim.data.HolderSets.identifier(potion.get("potion").getAsString()).equals("minecraft:water")
            || potion.has("custom_effects") && !potion.getAsJsonArray("custom_effects").isEmpty()
            || !convertible.contains(level.registry().block(level.stateAt(context.clickedPos())).key())) return SimInteraction.PASS;
        context.player().hand(context.hand(), ItemResults.filled(context.stack(), context.player(), items.stack("minecraft:glass_bottle", 1)));
        level.setBlock(context.clickedPos(), level.registry().block("minecraft:mud").defaultState(), 3);
        return SimInteraction.SUCCESS;
    }
    @Override public SimInteraction use(UseContext context) { return general.use(context); }
}
