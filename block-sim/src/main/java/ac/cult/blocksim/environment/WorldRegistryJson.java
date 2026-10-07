package ac.cult.blocksim.environment;

import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** The JSON bridge required by the remaining action runtime, without a vanilla codec. */
public final class WorldRegistryJson {
    private WorldRegistryJson() { }

    public static JsonObject dimension(NbtValue.Compound data) {
        var value = NbtJson.encode(data).getAsJsonObject();
        for (String field : List.of("has_fixed_time", "has_skylight", "has_ceiling", "has_ender_dragon_fight"))
            bool(value, field);
        if (value.has("attributes")) value.add("attributes", attributes(value.getAsJsonObject("attributes")));
        return value;
    }

    public static JsonObject attributes(JsonObject received) {
        var defaults = ClientWorldDefaults.defaults();
        var result = new JsonObject();
        received.entrySet().forEach(entry -> {
            String key = HolderSets.identifier(entry.getKey());
            if (!defaults.syncableAttributes().contains(key)) return;
            JsonElement value = entry.getValue().deepCopy();
            if (defaults.booleanAttributes().contains(key)) {
                if (value.isJsonObject()) bool(value.getAsJsonObject(), "argument");
                else value = new com.google.gson.JsonPrimitive(NbtJson.booleanValue(value));
            } else if (key.equals("minecraft:audio/background_music")) {
                var music = value.getAsJsonObject();
                if (music.has("modifier")) music = music.getAsJsonObject("argument");
                // PacketEvents BackgroundMusic / BiomeEffects.MusicSettings codec fields.
                for (String field : List.of("default", "creative", "underwater"))
                    if (music.has(field)) bool(music.getAsJsonObject(field), "replace_current_music");
            }
            result.add(key, value);
        });
        return result;
    }

    private static void bool(JsonObject value, String field) {
        if (value.has(field)) value.addProperty(field, NbtJson.booleanValue(value.get(field)));
    }
}
