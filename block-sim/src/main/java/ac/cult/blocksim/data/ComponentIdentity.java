package ac.cult.blocksim.data;

import ac.cult.blocksim.data.nbt.NbtJson;
import com.google.gson.JsonElement;
import java.util.HashSet;
import java.util.Set;

/** Semantic component values whose persistent list encoding has different equality. */
final class ComponentIdentity {
    static final String TOOLTIP_DISPLAY = "minecraft:tooltip_display";
    private ComponentIdentity() {}

    static Object tooltipDisplay(JsonElement value) {
        if (value == null) return null;
        var fields = value.getAsJsonObject();
        boolean hidden = fields.has("hide_tooltip") && NbtJson.booleanValue(fields.get("hide_tooltip"));
        var components = new HashSet<String>();
        if (fields.has("hidden_components")) fields.getAsJsonArray("hidden_components")
                .forEach(component -> components.add(HolderSets.identifier(component.getAsString())));
        return new TooltipDisplay(hidden, Set.copyOf(components));
    }
    private record TooltipDisplay(boolean hideTooltip, Set<String> hiddenComponents) {}
}
