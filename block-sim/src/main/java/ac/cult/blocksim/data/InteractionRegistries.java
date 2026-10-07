package ac.cult.blocksim.data;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Version defaults or the adapter's received registry snapshot; never server-world state. */
public final class InteractionRegistries {
    private static final class Defaults {
        static final InteractionRegistries VALUE;
        static { try { VALUE = load("26.3"); } catch (IOException e) { throw new ExceptionInInitializerError(e); } }
    }
    public static InteractionRegistries defaults() { return Defaults.VALUE; }
    private final JsonObject values;
    private final java.util.Map<String, java.util.Map<String, byte[]>> encoded;
    public InteractionRegistries(JsonObject values) { this(values.deepCopy(), java.util.Map.of()); }
    private InteractionRegistries(JsonObject values, java.util.Map<String, java.util.Map<String, byte[]>> encoded) {
        this.values = values;
        this.encoded = java.util.Map.copyOf(encoded);
    }
    public static InteractionRegistries load(String version) throws IOException {
        String path = "/block-sim/" + version + "/interaction-registries.json";
        var stream = InteractionRegistries.class.getResourceAsStream(path);
        if (stream == null) throw new IOException("Missing interaction registries " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new InteractionRegistries(JsonParser.parseReader(reader).getAsJsonObject());
        }
    }
    public JsonElement resolve(String registry, JsonElement holder) {
        if (!holder.isJsonPrimitive()) return holder.deepCopy();
        String key = holder.getAsString(); if (!key.contains(":")) key = "minecraft:" + key;
        var received = encoded.get(registry);
        if (received != null) {
            if (!received.containsKey(key)) throw new IllegalArgumentException("Unknown received " + registry + " entry " + key);
            byte[] data = received.get(key);
            // A selected known-pack reference resolves against the bundled definition.
            return data == null ? defaults().resolve(registry, holder)
                    : ac.cult.blocksim.data.nbt.NbtJson.encode(ac.cult.blocksim.data.nbt.BinaryNbt.read(data));
        }
        var result = values.getAsJsonObject(registry).get(key);
        if (result == null) throw new IllegalArgumentException("Unknown received " + registry + " entry " + key);
        return result.deepCopy();
    }
    public java.util.Map<String, JsonElement> entries(String registry) {
        var received = encoded.get(registry);
        if (received != null) {
            var result = new java.util.HashMap<String, JsonElement>();
            received.keySet().forEach(key -> result.put(key, resolve(registry, new JsonPrimitive(key))));
            return java.util.Map.copyOf(result);
        }
        if (!values.has(registry)) return java.util.Map.of();
        var result = new java.util.HashMap<String, JsonElement>();
        values.getAsJsonObject(registry).entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().deepCopy()));
        return java.util.Map.copyOf(result);
    }
    /** A received registry replaces that registry's defaults, including an empty replacement. */
    public InteractionRegistries withRegistry(String registry, java.util.Map<String, JsonElement> entries) {
        var replacement = new JsonObject();
        entries.forEach((key, value) -> replacement.add(key, value.deepCopy()));
        var snapshot = values.deepCopy();
        snapshot.add(registry, replacement);
        var remaining = new java.util.HashMap<>(encoded);
        remaining.remove(registry);
        return new InteractionRegistries(snapshot, remaining);
    }

    /** Retain immutable wire payloads; an unused definition never builds an NBT/JSON tree. */
    public InteractionRegistries withEncodedRegistry(String registry, java.util.Map<String, byte[]> entries) {
        var received = new java.util.HashMap<String, byte[]>();
        entries.forEach((key, data) -> received.put(key, data == null ? null : data.clone()));
        var snapshot = new java.util.HashMap<>(encoded);
        snapshot.put(registry, java.util.Collections.unmodifiableMap(received));
        return new InteractionRegistries(values, snapshot);
    }
}
