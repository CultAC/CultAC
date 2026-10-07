package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockEntityData;
import java.util.HashMap;
import java.util.Map;

/** Record identity and playback signal only; other item components stay uninterpreted. */
final class ClientJukeboxFields {
    private ClientJukeboxFields() {}

    static BlockEntityData load(NbtValue.Compound tag, BlockEntityData previous, InteractionRegistries registries) {
        var saved = new HashMap<String, NbtValue>();
        var item = record(tag.values().get("RecordItem"));
        if (item != null) saved.put("RecordItem", item);
        var components = components(item);
        var before = previous.savedData().values().get("RecordItem");
        boolean stopped = before != null
                && (item == null
                        || !((NbtValue.Compound) before)
                                .values()
                                .get("id")
                                .equals(item.values().get("id"))
                        || !components((NbtValue.Compound) before).equals(components));
        var fields = new HashMap<String, com.google.gson.JsonElement>();
        fields.put("song_playing", new com.google.gson.JsonPrimitive(false));
        // A load with no usable timer does not replace an already playing song.
        if (!stopped && previous.data().get("song_playing").getAsBoolean()) {
            for (String key : java.util.List.of("song_playing", "song_end_tick", "ticks_since_song_started"))
                fields.put(key, previous.data().get(key));
            saved.put("ticks_since_song_started", previous.savedData().values().get("ticks_since_song_started"));
        }
        var ticks = tag.values().get("ticks_since_song_started");
        var playable = components.get("minecraft:jukebox_playable");
        if (ticks instanceof NbtValue.Numeric number && playable != null) {
            long elapsed = number.kind() == NbtValue.Kind.FLOAT || number.kind() == NbtValue.Kind.DOUBLE
                    ? (long) Math.floor(number.value().doubleValue())
                    : number.value().longValue();
            var song =
                    registries.resolve("jukebox_song", NbtJson.encode(playable)).getAsJsonObject();
            int end = (int) Math.ceil(song.get("length_in_seconds").getAsFloat() * 20.0F) + 20;
            if (elapsed < end) {
                fields.put("song_playing", new com.google.gson.JsonPrimitive(true));
                fields.put("song_end_tick", new com.google.gson.JsonPrimitive(end));
                fields.put("ticks_since_song_started", new com.google.gson.JsonPrimitive(elapsed));
                saved.put("ticks_since_song_started", new NbtValue.Numeric(NbtValue.Kind.LONG, elapsed));
            }
        }
        if (item != null) {
            // The signal reader needs only the playable component. Keep the rest once in savedData.
            var projected = new HashMap<>(item.values());
            projected.put(
                    "components",
                    new NbtValue.Compound(
                            playable == null
                                    ? Map.of("!minecraft:jukebox_playable", new NbtValue.Compound(Map.of()))
                                    : Map.of("minecraft:jukebox_playable", playable)));
            fields.put("RecordItem", NbtJson.encode(new NbtValue.Compound(projected)));
        }
        return new BlockEntityData("minecraft:jukebox", new Components(fields), new NbtValue.Compound(saved));
    }

    private static Map<String, NbtValue> components(NbtValue.Compound item) {
        if (item == null) return Map.of();
        var values = new HashMap<>(ac.cult.cultac.utils.inventory.ItemUtil.modelItems()
                .defaults(((NbtValue.Text) item.values().get("id")).value())
                .encodedNbt()
                .values());
        if (item.values().get("components") instanceof NbtValue.Compound patch)
            patch.values().forEach((key, value) -> {
                if (key.startsWith("!")) values.remove(key.substring(1));
                else values.put(key, value);
            });
        return values;
    }

    private static NbtValue.Compound record(NbtValue input) {
        var basic = ClientContainerFields.item(input);
        if (basic == null) return null;
        var fields = new HashMap<>(basic.values());
        // Preserve the patch as an identity blob; do not decode books, text or nested items.
        if (((NbtValue.Compound) input).values().get("components") instanceof NbtValue.Compound components) {
            var patch = new HashMap<String, NbtValue>();
            components.values().forEach((key, value) -> {
                boolean removed = key.startsWith("!");
                String name = ClientContainerFields.reference(new NbtValue.Text(removed ? key.substring(1) : key));
                if (name != null) patch.put((removed ? "!" : "") + name, value);
            });
            fields.put("components", new NbtValue.Compound(patch));
        }
        return new NbtValue.Compound(fields);
    }
}
