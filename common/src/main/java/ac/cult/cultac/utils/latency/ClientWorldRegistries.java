package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.blocksim.environment.BiomeWeather;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

/** Connection-owned world definitions. Contents are immutable; tag reloads publish a new map. */
public final class ClientWorldRegistries {
    public static final String DIMENSIONS = "minecraft:dimension_type";
    public static final String BIOMES = "minecraft:worldgen/biome";
    public static final String TIMELINES = "minecraft:timeline";
    public static final String CLOCKS = "minecraft:world_clock";
    public static final String PAINTINGS = "minecraft:painting_variant";
    public static final List<String> REGISTRIES = List.of(DIMENSIONS, BIOMES, TIMELINES, CLOCKS, PAINTINGS);
    /** Older wires keep model defaults; these also keep the backend's own defined entries after them. */
    public static final Set<String> OLDER_RETAINED = Set.of(BIOMES, PAINTINGS);

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
                names = StreamSupport.stream(holders.getAsJsonArray().spliterator(), false)
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
                    .map(name -> Objects.requireNonNull(definitions.get(name), "Missing received timeline " + name))
                    .toList();
        }
    }

    private final Data defaults;
    private Data data;
    private TimelineGeneration timelines;
    private Map<String, PaintingSize> paintings;
    private String initialPainting;

    public ClientWorldRegistries(Data defaults) {
        this.defaults = defaults;
        publish(derive(defaults));
    }

    public Data snapshot() {
        return data;
    }

    /** Rain eligibility reads this connection's biome climate without retaining another decoded table. */
    public boolean canRain(String biome, BlockPos pos, int seaLevel) {
        for (var definition : data.registries().get(BIOMES)) {
            if (definition.key().equals(biome))
                return BiomeWeather.canRain(definition.value().data(), pos, seaLevel);
        }
        throw new IllegalArgumentException("Missing received biome " + biome);
    }

    /** One generation's definitions and tags with the values derived from them; publishing cannot fail. */
    record Generation(
            Data data, TimelineGeneration timelines, Map<String, PaintingSize> paintings, String initialPainting) {}

    /** Called at finish_configuration, after split packets have been collected in ID order. */
    public void finish(
            Map<String, List<RegistryEntry>> received,
            ProtocolVersion source,
            Map<String, RegistryTags.Payload> tags,
            boolean contentsReceived) {
        publish(stage(received, source, tags, contentsReceived));
    }

    /** The generation {@link #finish} publishes, built without changing this connection's registries. */
    Generation stage(
            Map<String, List<RegistryEntry>> received,
            ProtocolVersion source,
            Map<String, RegistryTags.Payload> tags,
            boolean contentsReceived) {
        if (source != ProtocolVersion.V26_3 && !contentsReceived) {
            // RegistryDataCollector.loadOnlyTags keeps the registries and replaces only the supplied tag sets.
            var next = data;
            for (var entry : tags.entrySet())
                if (REGISTRIES.contains(entry.getKey())) next = withTags(next, entry.getKey(), entry.getValue());
            return new Generation(next, timelines, paintings, initialPainting);
        }
        var generation = new Generation(data, timelines, paintings, initialPainting);
        if (source != ProtocolVersion.V26_3 || contentsReceived) {
            var registries = new HashMap<String, List<Definition>>();
            for (String registry : REGISTRIES) {
                var entries = received.get(registry);
                List<Definition> values;
                if (source == ProtocolVersion.V26_3)
                    values = entries == null
                            ? List.of()
                            : entries.stream()
                                    .map(entry -> decode(registry, entry, source))
                                    .toList();
                else values = older(registry, entries, source);
                registries.put(registry, values);
            }
            generation = derive(new Data(registries, source == ProtocolVersion.V26_3 ? Map.of() : olderTags(received)));
            var paintings = received.get(PAINTINGS);
            // Painting.defineSynchedData reads the client's first received variant.
            if (source != ProtocolVersion.V26_3
                    && paintings != null
                    && !paintings.isEmpty()
                    && generation
                            .paintings()
                            .containsKey(
                                    HolderSets.identifier(paintings.getFirst().name())))
                generation = new Generation(
                        generation.data(),
                        generation.timelines(),
                        generation.paintings(),
                        paintings.getFirst().name());
        }
        var next = generation.data();
        for (var entry : tags.entrySet()) {
            var payload = entry.getValue();
            if ((source == ProtocolVersion.V26_3 ? !contentsReceived || !payload.isEmpty() : !payload.isEmpty())
                    && REGISTRIES.contains(entry.getKey())) next = withTags(next, entry.getKey(), payload);
        }
        return new Generation(next, generation.timelines(), generation.paintings(), generation.initialPainting());
    }

    /** Model defaults; a dynamic family whose contents arrived starts untagged, as the client's fresh registry. */
    private Map<String, Map<String, List<String>>> olderTags(Map<String, List<RegistryEntry>> received) {
        var tags = new HashMap<>(defaults.tags());
        for (String registry : OLDER_RETAINED) if (received.containsKey(registry)) tags.put(registry, Map.of());
        return tags;
    }

    void publish(Generation generation) {
        data = generation.data();
        timelines = generation.timelines();
        timelines.tags = data.tags().getOrDefault(TIMELINES, Map.of());
        paintings = generation.paintings();
        initialPainting = generation.initialPainting();
    }

    /**
     * Publishes this connection's defaults after a generation failed to stage. Dimension IDs keep the client's
     * {@code dimensions} order; a name without a default definition resolves to nothing, never to another type.
     */
    void fallback(List<String> dimensions) {
        var data = defaults;
        if (dimensions != null) {
            var known = new HashMap<String, Definition>();
            for (var definition : defaults.registries().get(DIMENSIONS)) known.put(definition.key(), definition);
            var registries = new HashMap<>(defaults.registries());
            registries.put(
                    DIMENSIONS,
                    dimensions.stream()
                            .map(name -> known.getOrDefault(HolderSets.identifier(name), new Definition(name, null)))
                            .toList());
            data = new Data(registries, defaults.tags());
        }
        publish(derive(data));
    }

    /**
     * An older connection's published generation: received dimensions, otherwise model defaults followed by
     * {@link #olderAppended} entries. A tags-only configuration passes the retained received generation.
     */
    private List<Definition> older(String registry, List<RegistryEntry> entries, ProtocolVersion source) {
        if (registry.equals(DIMENSIONS))
            return entries == null
                    ? defaults.registries().get(registry)
                    : entries.stream()
                            .map(entry -> decode(registry, entry, source))
                            .toList();
        var base = defaults.registries().get(registry);
        if (entries == null || !OLDER_RETAINED.contains(registry)) return base;
        var defined = new LinkedHashMap<String, RegistryEntry>();
        for (var entry : entries) if (entry.data() != null) defined.putIfAbsent(entry.name(), entry);
        var result = new ArrayList<Definition>(base.size() + defined.size());
        // A painting's size is version-stable, so a received override replaces its default in place.
        for (var definition : base) {
            var override = registry.equals(PAINTINGS) ? defined.get(definition.key()) : null;
            result.add(override == null ? definition : decode(registry, override, source));
        }
        for (String name : olderAppended(registry, List.copyOf(defined.keySet())))
            result.add(decode(registry, defined.get(name), source));
        return List.copyOf(result);
    }

    /**
     * Defined (data-carrying) received names absent from the model defaults, in received order. Model names
     * and definitions both append exactly these, so their IDs agree.
     */
    public static List<String> olderAppended(String registry, List<String> defined) {
        if (!OLDER_RETAINED.contains(registry) || defined.isEmpty()) return List.of();
        var model = new HashSet<>(ClientWorldDefaults.defaults().initialNames(registry));
        var result = new LinkedHashSet<String>();
        for (String name : defined) if (!model.contains(name)) result.add(name);
        return List.copyOf(result);
    }

    private static Definition decode(String registry, RegistryEntry entry, ProtocolVersion source) {
        byte[] raw = entry.data();
        if (raw == null)
            return new Definition(entry.name(), ClientWorldDefaults.defaults().resolve(registry, entry.name()));
        var nbt = (NbtValue.Compound) BinaryNbt.read(raw);
        JsonObject json = registry.equals(DIMENSIONS)
                ? WorldRegistryJson.dimension(nbt)
                : NbtJson.encode(nbt).getAsJsonObject();
        String encoded = registry.equals(DIMENSIONS)
                ? ModelDimensions.project(entry.name(), json.toString(), source, ProtocolVersion.V26_3)
                : json.toString();
        return new Definition(entry.name(), new ClientWorldDefaults.Entry(nbt, encoded));
    }

    private static Generation derive(Data next) {
        var timelines = new TimelineGeneration(
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
        var variants = next.registries().getOrDefault(PAINTINGS, List.of());
        return new Generation(
                next,
                timelines,
                Map.copyOf(sizes),
                variants.isEmpty() ? null : variants.getFirst().key());
    }

    /** Painting.defineSynchedData uses Registry.getAny: the client's first received variant. */
    public PaintingSize initialPainting() {
        if (initialPainting == null) throw new NoSuchElementException("No painting variants");
        return painting(initialPainting);
    }

    public PaintingSize painting(String key) {
        return Objects.requireNonNull(
                paintings.get(HolderSets.identifier(key)), "Missing received painting variant " + key);
    }

    /** Only fitting sizes matter to local consumption; artwork selection stays random in the client. */
    public List<PaintingSize> placeablePaintings() {
        return data.tags().getOrDefault(PAINTINGS, Map.of()).getOrDefault("minecraft:placeable", List.of()).stream()
                .map(this::painting)
                .toList();
    }

    public void applyTags(String registry, RegistryTags.Payload payload) {
        data = withTags(data, registry, payload);
        if (registry.equals(TIMELINES)) timelines.tags = data.tags().getOrDefault(TIMELINES, Map.of());
    }

    private static Data withTags(Data data, String registry, RegistryTags.Payload payload) {
        var entries = data.registries().get(registry);
        if (entries == null) return data;
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
        return new Data(data.registries(), tags);
    }

    public DimensionData.Binding dimension(int id) {
        var entries = data.registries().get(DIMENSIONS);
        if (id < 0 || id >= entries.size()) throw new IllegalArgumentException("Missing received dimension ID " + id);
        return dimension(entries.get(id));
    }

    /** The dimension with every value a level uses already read, so data CultAC cannot model throws here. */
    public DimensionData.Binding readableDimension(int id) {
        var binding = dimension(id);
        var type = binding.dimension();
        type.minY();
        type.height();
        type.hasFastLava();
        type.hasSkyLight();
        binding.environment().get();
        return binding;
    }

    public DimensionData.Binding dimension(String key) {
        String name = HolderSets.identifier(key);
        return dimension(data.registries().get(DIMENSIONS).stream()
                .filter(entry -> entry.key().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing received dimension " + key)));
    }

    private DimensionData.Binding dimension(Definition entry) {
        if (entry.value() == null) throw new IllegalArgumentException("Unresolvable received dimension " + entry.key());
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
