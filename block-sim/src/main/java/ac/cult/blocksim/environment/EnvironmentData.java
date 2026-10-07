package ac.cult.blocksim.environment;

import ac.cult.blocksim.engine.ClientClocks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable environment metadata captured at the same boundary as the compensated dimension. */
public final class EnvironmentData {
    private final JsonObject dimensionAttributes;
    private final Map<Integer, JsonObject> biomeAttributes;
    private final List<JsonElement> timelines;

    public EnvironmentData(JsonObject dimensionAttributes, Map<Integer, JsonObject> biomeAttributes, List<JsonElement> timelines) {
        this.dimensionAttributes = dimensionAttributes.deepCopy();
        var biomes = new HashMap<Integer, JsonObject>();
        biomeAttributes.forEach((id, attributes) -> biomes.put(id, attributes.deepCopy()));
        this.biomeAttributes = Map.copyOf(biomes);
        this.timelines = timelines.stream().map(JsonElement::deepCopy).toList();
    }
    public BooleanEnvironment create(ClientClocks clocks) {
        return new BooleanEnvironment(dimensionAttributes, biomeAttributes, timelines, clocks);
    }
}
