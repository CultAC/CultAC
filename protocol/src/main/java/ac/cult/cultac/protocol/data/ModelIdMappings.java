package ac.cult.cultac.protocol.data;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import java.util.Map;

/** Composed ViaVersion numeric mappings, including vanilla identifier and property renames. */
public final class ModelIdMappings {
    private final Map<String, int[]> ids;

    /** The isolated provider transfers ownership of its validated arrays to this neutral view. */
    public ModelIdMappings(Map<String, int[]> ids) {
        this.ids = Map.copyOf(ids);
    }

    /** Source must be older than target; newer content cannot be inverted by guessing a fallback. */
    public static ModelIdMappings load(ProtocolVersion source, ProtocolVersion target) {
        if (source.protocol() >= target.protocol())
            throw new IllegalArgumentException("Expected an older source protocol");
        return project(source, target);
    }

    /**
     * Explicit directed projection, including the actual ViaBackwards client-visible fallbacks.
     * Via supplies the directed path and numeric mapping data; the shared provider caches the result.
     */
    public static ModelIdMappings project(ProtocolVersion source, ProtocolVersion target) {
        return ProtocolCodecs.decoder().mappings(source, target);
    }

    public int blockState(int sourceId) {
        return mapped("blockstates", sourceId);
    }

    public int item(int sourceId) {
        return mapped("items", sourceId);
    }

    /** Via's entity mapping data, including ViaBackwards fallbacks such as happy_ghast -> ghast. */
    public int entity(int sourceId) {
        return mapped("entities", sourceId);
    }

    /**
     * The model-registry entity type an older client actually simulates. The model is never older
     * than the observed stream, so the model -> client path applies exactly Via's downgrade fallbacks.
     */
    public static String clientVisibleEntity(String modelType, ProtocolVersion model, ProtocolVersion client) {
        if (client.atLeast(model)) return modelType;
        var types = ModelRegistryData.load(model).registry("minecraft:entity_type");
        int visible = project(model, client).entity(types.id(modelType));
        if (visible < 0) throw new ProtocolResolutionException("No client-visible entity for " + modelType);
        return types.name(project(client, model).entity(visible));
    }

    public int entityCount() {
        var values = ids.get("entities");
        if (values == null) throw new ProtocolResolutionException("Missing directed entity mappings");
        return values.length;
    }

    public int block(int sourceId) {
        return mapped("blocks", sourceId);
    }

    public int sound(int sourceId) {
        return mapped("sounds", sourceId);
    }

    private int mapped(String key, int id) {
        var values = ids.get(key);
        if (id < 0 || id >= values.length) throw new MalformedPacketException("Unknown source " + key + " ID " + id);
        return values[id];
    }

    public int blockStateCount() {
        return ids.get("blockstates").length;
    }

    public int itemCount() {
        return ids.get("items").length;
    }

    public int blockCount() {
        return ids.get("blocks").length;
    }
}
