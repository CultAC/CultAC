package ac.cult.blocksim.environment;

import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.engine.ClientClocks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compiled dimension/biome booleans and ordered timelines, using compensated client clocks. */
public final class BooleanEnvironment {
    public static final String WATER = "gameplay/water_evaporates", CREAKING = "gameplay/creaking_active";
    public record Values(boolean waterEvaporates, boolean creakingActive) { }
    private record Layer(String clock, BooleanTrack water, BooleanTrack creaking) { }
    private final Map<Integer, Integer> biomeBases;
    private final List<Layer> layers;
    private final ClientClocks clocks;
    private final Values[] values = new Values[4];

    public BooleanEnvironment(JsonObject dimensionAttributes, Map<Integer, JsonObject> biomeAttributes,
                              List<JsonElement> timelines, ClientClocks clocks) {
        this.clocks = java.util.Objects.requireNonNull(clocks);
        boolean water = BooleanRule.apply(dimensionAttributes, WATER, false);
        boolean creaking = BooleanRule.apply(dimensionAttributes, CREAKING, false);
        var bases = new HashMap<Integer, Integer>();
        biomeAttributes.forEach((id, attributes) -> bases.put(id, (BooleanRule.apply(attributes, WATER, water) ? 1 : 0)
                | (BooleanRule.apply(attributes, CREAKING, creaking) ? 2 : 0)));
        biomeBases = Map.copyOf(bases);
        var layers = new ArrayList<Layer>();
        for (var definition : timelines) {
            var timeline = definition.getAsJsonObject();
            var tracks = timeline.getAsJsonObject("tracks");
            var waterTrack = entry(tracks, WATER); var creakingTrack = entry(tracks, CREAKING);
            if (waterTrack == null && creakingTrack == null) continue;
            Integer period = timeline.has("period_ticks") ? timeline.get("period_ticks").getAsInt() : null;
            var clock = timeline.get("clock");
            if (!clock.isJsonPrimitive()) throw new IllegalStateException("Timeline requires a named client clock");
            layers.add(new Layer(HolderSets.identifier(clock.getAsString()), waterTrack == null ? null : BooleanTrack.read(waterTrack, period),
                    creakingTrack == null ? null : BooleanTrack.read(creakingTrack, period)));
        }
        this.layers = List.copyOf(layers);
        tick();
    }
    private static JsonElement entry(JsonObject object, String key) {
        if (object == null) return null;
        var value = object.get(key);
        return value == null ? object.get("minecraft:" + key) : value;
    }
    public void tick() {
        for (int base = 0; base < values.length; base++) {
            boolean water = (base & 1) != 0, creaking = (base & 2) != 0;
            for (var layer : layers) if (layer.water() != null) water = layer.water().apply(clocks.state(layer.clock()).totalTicks(), water);
            for (var layer : layers) if (layer.creaking() != null) creaking = layer.creaking().apply(clocks.state(layer.clock()).totalTicks(), creaking);
            values[base] = new Values(water, creaking);
        }
    }
    public Values at(int biomeId) {
        var base = biomeBases.get(biomeId);
        if (base == null) throw new IllegalStateException("Missing received biome " + biomeId);
        return values[base];
    }
}
