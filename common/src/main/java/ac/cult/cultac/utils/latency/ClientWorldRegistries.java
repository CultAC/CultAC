package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.blocksim.environment.DimensionData;
import ac.cult.blocksim.environment.EnvironmentData;
import ac.cult.blocksim.environment.WorldRegistryJson;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry;
import ac.cult.cultac.utils.minecraft.ModelDimensions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Connection-owned world definitions. Contents are immutable; tag reloads publish a new map. */
public final class ClientWorldRegistries {
    public static final String DIMENSIONS = "minecraft:dimension_type";
    public static final String BIOMES = "minecraft:worldgen/biome";
    public static final String TIMELINES = "minecraft:timeline";
    public static final String CLOCKS = "minecraft:world_clock";
    public static final String PAINTINGS = "minecraft:painting_variant";
    public static final List<String> REGISTRIES = List.of(DIMENSIONS, BIOMES, TIMELINES, CLOCKS, PAINTINGS);

    public record Definition(String key, ClientWorldDefaults.Entry value) {}

    public record Data(Map<String, List<Definition>> registries, Map<String, Map<String, List<String>>> tags) {
        public Data {
            var contents = new HashMap<String, List<Definition>>();
            registries.forEach((key, entries) -> contents.put(key, List.copyOf(entries)));
            registries = Map.copyOf(contents);
            var memberships = new HashMap<String, Map<String, List<String>>>();
            tags.forEach((key, payload) -> {
                var named = new HashMap<String, List<String>>();
                payload.forEach((name, entries) -> named.put(name, List.copyOf(entries)));
                memberships.put(key, Map.copyOf(named));
            });
            tags = Map.copyOf(memberships);
        }
    }

    private static final class ModelDefaults {
        static final Data VALUE = load();

        private static Data load() {
            var defaults = ClientWorldDefaults.defaults();
            var values = new HashMap<String, List<Definition>>();
            var tags = new HashMap<String, Map<String, List<String>>>();
            for (String registry : REGISTRIES) {
                values.put(
                        registry,
                        defaults.initialNames(registry).stream()
                                .map(name -> new Definition(name, defaults.resolve(registry, name)))
                                .toList());
                tags.put(registry, defaults.tags(registry));
            }
            return new Data(values, tags);
        }
    }

    public static Data modelDefaults() {
        return ModelDefaults.VALUE;
    }

    /** A captured dimension retains its original timeline registry across replacement. */
    private static final class TimelineGeneration {
        final Map<String, JsonElement> definitions;
        Map<String, List<String>> tags;

        TimelineGeneration(List<Definition> entries, Map<String, List<String>> tags) {
            var values = new HashMap<String, JsonElement>();
            entries.forEach(entry ->
                    values.put(entry.key(), JsonParser.parseString(entry.value().json())));
            definitions = Map.copyOf(values);
            this.tags = tags;
        }

        List<JsonElement> resolve(JsonElement holders) {
            if (holders == null) return List.of();
            List<String> names;
            if (holders.isJsonArray())
                names = java.util.stream.StreamSupport.stream(
                                holders.getAsJsonArray().spliterator(), false)
                        .map(value -> HolderSets.identifier(value.getAsString()))
                        .toList();
            else {
                String name = holders.getAsString();
                names = name.startsWith("#")
                        ? tags.get(HolderSets.identifier(name.substring(1)))
                        : List.of(HolderSets.identifier(name));
                if (names == null) throw new IllegalArgumentException("Missing received timeline tag " + name);
            }
            return names.stream()
                    .map(name -> java.util.Objects.requireNonNull(
                            definitions.get(name), "Missing received timeline " + name))
                    .toList();
        }
    }

    private final Data defaults;
    private Data data;
    private TimelineGeneration timelines;
    private Map<String, PaintingSize> paintings;

    public ClientWorldRegistries(Data defaults) {
        this.defaults = defaults;
        replace(defaults);
    }

    public Data snapshot() {
        return data;
    }

    /** Rain eligibility reads this connection's biome climate without retaining another decoded table. */
    public boolean canRain(String biome, ac.cult.blocksim.engine.BlockPos pos, int seaLevel) {
        for (var definition : data.registries().get(BIOMES)) {
            if (definition.key().equals(biome))
                return ac.cult.blocksim.environment.BiomeWeather.canRain(
                        definition.value().data(), pos, seaLevel);
        }
        throw new IllegalArgumentException("Missing received biome " + biome);
    }

    /** Called at finish_configuration, after split packets have been collected in ID order. */
    public void finish(
            Map<String, List<RegistryEntry>> received,
            ProtocolVersion source,
            Map<String, RegistryTags.Payload> tags,
            boolean contentsReceived) {
        if (source != ProtocolVersion.V26_3 || contentsReceived) {
            var registries = new HashMap<String, List<Definition>>();
            for (String registry : REGISTRIES) {
                boolean useReceived = source == ProtocolVersion.V26_3 || registry.equals(DIMENSIONS);
                var entries = useReceived && (source == ProtocolVersion.V26_3 || contentsReceived)
                        ? received.get(registry)
                        : null;
                var values = entries == null
                        ? (source == ProtocolVersion.V26_3
                                ? List.<Definition>of()
                                : defaults.registries().get(registry))
                        : entries.stream()
                                .map(entry -> decode(registry, entry, source))
                                .toList();
                registries.put(registry, values);
            }
            // Older modeled connections retain model defaults except their received dimensions.
            replace(new Data(registries, source == ProtocolVersion.V26_3 ? Map.of() : defaults.tags()));
        }
        tags.forEach((registry, payload) -> {
            if ((source == ProtocolVersion.V26_3 ? !contentsReceived || !payload.isEmpty() : !payload.isEmpty())
                    && REGISTRIES.contains(registry)) applyTags(registry, payload);
        });
    }

    private static Definition decode(String registry, RegistryEntry entry, ProtocolVersion source) {
        byte[] raw = entry.data();
        if (raw == null)
            return new Definition(entry.name(), ClientWorldDefaults.defaults().resolve(registry, entry.name()));
        var nbt = (NbtValue.Compound) BinaryNbt.read(raw);
        JsonObject json = registry.equals(DIMENSIONS)
                ? WorldRegistryJson.dimension(nbt)
                : ac.cult.blocksim.data.nbt.NbtJson.encode(nbt).getAsJsonObject();
        String encoded = registry.equals(DIMENSIONS)
                ? ModelDimensions.project(entry.name(), json.toString(), source, ProtocolVersion.V26_3)
                : json.toString();
        return new Definition(entry.name(), new ClientWorldDefaults.Entry(nbt, encoded));
    }

    private void replace(Data next) {
        data = next;
        timelines = new TimelineGeneration(
                next.registries().get(TIMELINES), next.tags().getOrDefault(TIMELINES, Map.of()));
        var sizes = new HashMap<String, PaintingSize>();
        for (var entry : next.registries().getOrDefault(PAINTINGS, List.of())) {
            var fields = entry.value().data().values();
            sizes.put(
                    entry.key(),
                    new PaintingSize(
                            ((NbtValue.Numeric) fields.get("width")).value().intValue(),
                            ((NbtValue.Numeric) fields.get("height")).value().intValue()));
        }
        paintings = Map.copyOf(sizes);
    }

    /** Painting.defineSynchedData uses Registry.getAny: the first entry in wire ID order. */
    public PaintingSize initialPainting() {
        return painting(
                data.registries().getOrDefault(PAINTINGS, List.of()).getFirst().key());
    }

    public PaintingSize painting(String key) {
        return java.util.Objects.requireNonNull(
                paintings.get(HolderSets.identifier(key)), "Missing received painting variant " + key);
    }

    /** Only fitting sizes matter to local consumption; artwork selection stays random in the client. */
    public List<PaintingSize> placeablePaintings() {
        return data.tags().getOrDefault(PAINTINGS, Map.of()).getOrDefault("minecraft:placeable", List.of()).stream()
                .map(this::painting)
                .toList();
    }

    public void applyTags(String registry, RegistryTags.Payload payload) {
        var entries = data.registries().get(registry);
        if (entries == null) return;
        var named = new HashMap<String, List<String>>();
        payload.entries()
                .forEach((name, ids) -> named.put(
                        name,
                        ids.stream()
                                // TagNetworkSerialization drops unknown IDs and retains list order and duplicates.
                                .filter(id -> id >= 0 && id < entries.size())
                                .map(id -> entries.get(id).key())
                                .toList()));
        var tags = new HashMap<>(data.tags());
        tags.put(registry, Map.copyOf(named));
        data = new Data(data.registries(), tags);
        if (registry.equals(TIMELINES)) timelines.tags = data.tags().get(TIMELINES);
    }

    public DimensionData.Binding dimension(int id) {
        var entries = data.registries().get(DIMENSIONS);
        if (id < 0 || id >= entries.size()) throw new IllegalArgumentException("Missing received dimension ID " + id);
        return dimension(entries.get(id));
    }

    public DimensionData.Binding dimension(String key) {
        String name = HolderSets.identifier(key);
        return dimension(data.registries().get(DIMENSIONS).stream()
                .filter(entry -> entry.key().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing received dimension " + key)));
    }

    private DimensionData.Binding dimension(Definition entry) {
        var dimension = new DimensionData(
                entry.key(), entry.value().data(), entry.value().json());
        var capturedTimelines = timelines;
        var json = JsonParser.parseString(dimension.json()).getAsJsonObject();
        return new DimensionData.Binding(dimension, () -> {
            var biomeAttributes = new HashMap<Integer, JsonObject>();
            var biomes = data.registries().get(BIOMES);
            for (int id = 0; id < biomes.size(); id++)
                biomeAttributes.put(
                        id,
                        attributes(JsonParser.parseString(biomes.get(id).value().json())
                                .getAsJsonObject()));
            return new EnvironmentData(
                    attributes(json), biomeAttributes, capturedTimelines.resolve(json.get("timelines")));
        });
    }

    private static JsonObject attributes(JsonObject definition) {
        return definition.has("attributes") ? definition.getAsJsonObject("attributes") : new JsonObject();
    }
}
