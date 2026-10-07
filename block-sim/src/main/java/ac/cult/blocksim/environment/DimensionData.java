package ac.cult.blocksim.environment;

import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import com.google.gson.JsonObject;

/** Received dimension definition, retaining its complete immutable codec payload. */
public record DimensionData(String key, NbtValue.Compound data, String json) {
    public record Binding(DimensionData dimension, java.util.function.Supplier<EnvironmentData> environment) { }
    public DimensionData {
        key = HolderSets.identifier(key);
        java.util.Objects.requireNonNull(data);
        java.util.Objects.requireNonNull(json);
    }

    public int minY() { return number("min_y"); }
    public int height() { return number("height"); }
    public boolean hasSkyLight() { return NbtJson.booleanValue(NbtJson.encode(required("has_skylight"))); }
    public boolean hasCeiling() { return NbtJson.booleanValue(NbtJson.encode(required("has_ceiling"))); }
    public boolean hasFastLava() { return BooleanRule.apply(attributes(), "gameplay/fast_lava", false); }
    public JsonObject attributes() {
        var attributes = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("attributes");
        return attributes == null ? new JsonObject() : attributes;
    }
    private int number(String name) { return ((NbtValue.Numeric) required(name)).value().intValue(); }
    private NbtValue required(String name) {
        var value = data.values().get(name);
        if (value == null) throw new IllegalArgumentException("Missing received dimension " + name);
        return value;
    }
}
