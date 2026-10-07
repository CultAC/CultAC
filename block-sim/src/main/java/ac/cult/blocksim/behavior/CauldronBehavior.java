package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.interaction.*;

/** Cauldron client callbacks choose a result; every inventory and world mutation is server-only. */
public final class CauldronBehavior extends BlockBehavior {
    private final DataTables data;
    public CauldronBehavior(DataTables data) { this.data = data; }
    @Override public SimInteraction useItemOn(int state, UseContext context) {
        var stack = context.stack(); String item = stack.itemKey(); var level = context.level();
        String dispatcher = level.registry().block(state).bindings().get("AbstractCauldronBlock.interactions");
        boolean water = dispatcher.equals("water");
        if (water && data.tags().get("item:minecraft:cauldron_can_remove_dye").contains(item))
            return stack.components().has("minecraft:dyed_color") ? SimInteraction.SUCCESS : SimInteraction.TRY_WITH_EMPTY_HAND;
        if (item.equals("minecraft:water_bucket")) return SimInteraction.SUCCESS;
        if (item.equals("minecraft:lava_bucket") || item.equals("minecraft:powder_snow_bucket"))
            return data.tags().get("fluid:minecraft:water").contains(level.fluidAt(context.clickedPos().relative(Direction.UP)).type())
                ? SimInteraction.CONSUME : SimInteraction.SUCCESS;
        if (item.equals("minecraft:bucket")) return dispatcher.equals("lava")
            || (water || dispatcher.equals("powder_snow")) && number(level, state, "level") == 3 ? SimInteraction.SUCCESS : SimInteraction.TRY_WITH_EMPTY_HAND;
        if (water && item.equals("minecraft:glass_bottle")) return SimInteraction.SUCCESS;
        if (item.equals("minecraft:potion") && (dispatcher.equals("empty") || water && number(level, state, "level") < 3)) {
            var contents = stack.components().get("minecraft:potion_contents");
            if (contents != null) {
                var potion = contents.isJsonPrimitive() ? contents : contents.getAsJsonObject().get("potion");
                boolean custom = contents.isJsonObject() && contents.getAsJsonObject().has("custom_effects") && !contents.getAsJsonObject().getAsJsonArray("custom_effects").isEmpty();
                if (potion != null && (potion.getAsString().equals("minecraft:water") || potion.getAsString().equals("water")) && !custom) return SimInteraction.SUCCESS;
            }
        }
        if (water && data.tags().get("item:minecraft:banners").contains(item)) {
            var patterns = stack.components().get("minecraft:banner_patterns");
            return patterns != null && !patterns.getAsJsonArray().isEmpty() ? SimInteraction.SUCCESS : SimInteraction.TRY_WITH_EMPTY_HAND;
        }
        if (water && data.tags().get("item:minecraft:shulker_boxes").contains(item) && !item.equals("minecraft:shulker_box")) return SimInteraction.SUCCESS;
        return SimInteraction.TRY_WITH_EMPTY_HAND;
    }
}
