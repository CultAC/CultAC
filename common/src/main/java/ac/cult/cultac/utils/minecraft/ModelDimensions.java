package ac.cult.cultac.utils.minecraft;

import ac.cult.cultac.protocol.ProtocolVersion;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** Dimension codec compatibility for received geometry and gameplay attributes. */
public final class ModelDimensions {
    private ModelDimensions() {}

    public static String project(String typeKey, String json, ProtocolVersion source, ProtocolVersion target) {
        if (json == null || source == target) return json;
        java.util.Objects.requireNonNull(typeKey, "Dimension type registry key");
        String key = typeKey.startsWith("minecraft:") ? typeKey.substring(10) : typeKey;
        var dimension = JsonParser.parseString(json).getAsJsonObject();
        if (source.protocol() < 774 && target.protocol() >= 774) {
            var attributes = new JsonObject();
            dimension.add("attributes", attributes);
            if (dimension.remove("fixed_time") != null) dimension.addProperty("has_fixed_time", true);
            if (key.equals("the_nether")) attributes.addProperty("gameplay/sky_light_level", 4F);
            attributes.addProperty("gameplay/can_start_raid", bool(dimension.get("has_raids"), true));
            attributes.addProperty("gameplay/piglins_zombify", !bool(dimension.get("piglin_safe"), true));
            attributes.addProperty("gameplay/respawn_anchor_works", bool(dimension.get("respawn_anchor_works"), true));
            boolean ultrawarm = bool(dimension.get("ultrawarm"), false);
            attributes.addProperty("gameplay/fast_lava", ultrawarm);
            attributes.addProperty("gameplay/water_evaporates", ultrawarm);
        }
        // Required by the 26.x dimension codec. Skybox, lighting, timelines and
        // clocks are optional: vanilla supplies their defaults without copied
        // fog, music, cloud or particle configuration.
        if (source.protocol() < 775 && target.protocol() >= 775)
            dimension.addProperty("has_ender_dragon_fight", key.equals("the_end"));
        var attributes = dimension.getAsJsonObject("attributes");
        if (attributes != null && source.protocol() >= 777 && target.protocol() <= 776) {
            // ViaBackwards 26.3 -> 26.2 converts the new modifier names.
            attributes.entrySet().forEach(entry -> {
                if (!entry.getValue().isJsonObject()) return;
                var value = entry.getValue().getAsJsonObject();
                var modifier = value.get("modifier");
                if (modifier != null && List.of("append", "overlay").contains(modifier.getAsString()))
                    value.addProperty("modifier", "override");
            });
        }
        if (attributes != null && source.protocol() >= 775 && target.protocol() <= 774) {
            // These keys cannot be decoded by the 1.21.11 attribute registry.
            for (String name :
                    List.of("visual/block_light_tint", "visual/night_vision_color", "visual/ambient_light_color")) {
                attributes.remove(name);
                attributes.remove("minecraft:" + name);
            }
        }
        if (source.protocol() >= 774 && target.protocol() < 774) {
            boolean special = key.equals("the_nether") || key.equals("the_end");
            dimension.addProperty("effects", "minecraft:" + (special ? key : "overworld"));
            dimension.addProperty("natural", !special);
            var cloud = attribute(attributes, "visual/cloud_height");
            if (cloud != null) dimension.add("cloud_height", cloud);
            dimension.addProperty("has_raids", bool(attribute(attributes, "gameplay/can_start_raid"), true));
            dimension.addProperty("piglin_safe", !bool(attribute(attributes, "gameplay/piglins_zombify"), true));
            dimension.addProperty(
                    "respawn_anchor_works", bool(attribute(attributes, "gameplay/respawn_anchor_works"), false));
            var bed = attribute(attributes, "gameplay/bed_rule");
            dimension.addProperty(
                    "bed_works",
                    bed == null
                            || bool(bed.getAsJsonObject().get("can_sleep"), false)
                            || bool(bed.getAsJsonObject().get("can_set_spawn"), false));
            dimension.addProperty("ultrawarm", bool(attribute(attributes, "gameplay/fast_lava"), false));
        }
        return dimension.toString();
    }

    private static JsonElement attribute(JsonObject attributes, String key) {
        if (attributes == null) return null;
        var value = attributes.get(key);
        return value == null ? attributes.get("minecraft:" + key) : value;
    }

    private static boolean bool(JsonElement value, boolean fallback) {
        if (value == null) return fallback;
        // NbtOps -> JsonOps represents boolean ByteTags as numbers.
        return value.getAsJsonPrimitive().isNumber() ? value.getAsByte() != 0 : value.getAsBoolean();
    }
}
