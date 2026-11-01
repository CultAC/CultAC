package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.BlockSimulator;
import ac.cult.blocksim.behavior.ConsumableBehavior;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.network.packet.RegistryData;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.utils.blockplace.BlockSimulatorWorldView;
import ac.cult.cultac.utils.inventory.ItemUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Received block, item, fluid and entity type memberships at the compensated boundary.
 * Movement and block actions use immutable named tag snapshots.
 */
public final class ClientComponentRegistries {
    private final Map<String, RegistryTags.Payload> tags = new HashMap<>();
    private Tags geometryTags;

    private record ActionPath(ProtocolVersion source, ProtocolVersion client, ProtocolVersion model) {}

    private final Map<ActionPath, Tags> actionTags = new HashMap<>();
    /** The last {@link #clientTags} answer, which per-cell fluid queries repeat; cleared with {@link #actionTags}. */
    private Tags lastClientTags;

    private ProtocolVersion lastClientTagsSource;
    private ProtocolVersion lastClientTagsClient;
    private final Map<ActionPath, HolderSets.Overlay> simulatorTags = new HashMap<>();
    private Map<String, RegistryTags.Payload> configurationTags;
    private Map<String, List<WireValueDecoder.RegistryEntry>> configurationRegistries;
    private Map<String, List<WireValueDecoder.RegistryEntry>> receivedRegistries = Map.of();
    private ProtocolVersion receivedRegistryVersion;
    private ClientWorldRegistries worldRegistries;
    private DataTables simulationDefaults;
    private SimulationRegistries simulationRegistries;
    private SimulationActions simulationActions;

    public ClientWorldRegistries worldRegistries(Supplier<ClientWorldRegistries.Data> initial) {
        if (worldRegistries == null) {
            worldRegistries = new ClientWorldRegistries(initial.get());
            if (receivedRegistryVersion != null)
                worldRegistries.finish(receivedRegistries, receivedRegistryVersion, tags, true);
            else tags.forEach(worldRegistries::applyTags);
        }
        return worldRegistries;
    }

    public record SimulationRegistries(ItemRegistry items, InteractionRegistries interactions) {}

    public record SimulationActions(
            ProtocolVersion source,
            ProtocolVersion client,
            Set<String> features,
            SimulationRegistries registries,
            HolderSets.Overlay tags,
            ModelBlockStates states,
            BlockSimulator simulator) {}

    /** One immutable binding per received registry/tag/feature generation, reused across actions. */
    public SimulationActions blockSimulatorActions(
            ProtocolVersion source, ProtocolVersion client, Set<String> features, DataTables defaults) {
        var registries = blockSimulatorRegistries(defaults);
        var tags = blockSimulatorTags(source, client, defaults);
        var previous = simulationActions;
        if (previous != null
                && previous.source() == source
                && previous.client() == client
                && previous.registries() == registries
                && previous.tags() == tags
                && previous.features().equals(features)) return previous;
        var states = previous != null && previous.source() == source && previous.client() == client
                ? previous.states()
                : ModelBlockStates.project(source, client, ProtocolVersion.V26_3);
        var simulator = BlockSimulator.create(
                defaults.withEnabledFeatures(features),
                registries.items(),
                registries.interactions(),
                BlockSimulatorWorldView::placementUnobstructed,
                // Living-effect listeners have no field in the existing block-action result contract.
                new ConsumableBehavior(contextUse -> {}),
                tags);
        simulationActions =
                new SimulationActions(source, client, Set.copyOf(features), registries, tags, states, simulator);
        return simulationActions;
    }

    /** Bundled item defaults and this connection's received interaction definitions. */
    public SimulationRegistries blockSimulatorRegistries(DataTables defaults) {
        if (simulationRegistries == null || simulationDefaults != defaults) {
            simulationRegistries = new SimulationRegistries(
                    defaults == DataTables.defaults() ? ItemUtil.modelItems() : new ItemRegistry(defaults),
                    receivedInteractions(InteractionRegistries.defaults()));
            simulationDefaults = defaults;
        }
        return simulationRegistries;
    }

    private InteractionRegistries receivedInteractions(InteractionRegistries bindings) {
        // The existing older-model registry producer retains model defaults for these
        // families. Received older NBT stays opaque until its schema is projected.
        if (receivedRegistryVersion != ProtocolVersion.V26_3) return bindings;
        var result = bindings;
        for (String registry :
                List.of("enchantment", "instrument", "jukebox_song", "block_transformer", "block_state_provider")) {
            var entries = receivedRegistries.get("minecraft:" + registry);
            if (entries == null) continue;
            var values = new LinkedHashMap<String, byte[]>();
            for (var entry : entries) values.put(entry.name(), entry.data());
            result = result.withEncodedRegistry(registry, values);
        }
        return result;
    }

    /** Immutable received memberships; null means this registry has not received a replacement. */
    public RegistryTags.Payload tagPayload(String registry) {
        return tags.get(registry);
    }

    /** Configuration collects replacements before applying them to the inherited bindings. */
    public void beginConfiguration() {
        configurationTags = new LinkedHashMap<>();
        configurationRegistries = null;
    }

    public void appendConfigurationTags(RegistryTags packet) {
        if (configurationTags == null) beginConfiguration();
        configurationTags.putAll(packet.tags());
    }

    /** RegistryDataCollector.ContentsCollector appends split packets in wire-ID order, including empty registries. */
    public void appendConfigurationRegistryData(RegistryData packet) {
        if (configurationTags == null) beginConfiguration();
        if (configurationRegistries == null) configurationRegistries = new LinkedHashMap<>();
        var entries = new ArrayList<>(configurationRegistries.getOrDefault(packet.registry(), List.of()));
        entries.addAll(packet.entries());
        configurationRegistries.put(packet.registry(), List.copyOf(entries));
    }

    /** Published at finish_configuration; tags-only reconfiguration retains the previous contents. */
    public Map<String, List<WireValueDecoder.RegistryEntry>> receivedRegistries() {
        return receivedRegistries;
    }

    /** The protocol {@link #receivedRegistries} were observed on; null before any were published. */
    public ProtocolVersion receivedRegistryVersion() {
        return receivedRegistryVersion;
    }

    /**
     * Via appends registry_data to every finish_configuration when upgrading across these versions:
     * EntityPacketRewriter1_21_5, EntityPacketRewriter1_21_6, Protocol1_21_11To26_1 and Protocol26_2To26_3.
     */
    static boolean viaAppendsRegistryData(ProtocolVersion observed, ProtocolVersion client) {
        return List.of(ProtocolVersion.V1_21_5, ProtocolVersion.V1_21_6, ProtocolVersion.V26_1, ProtocolVersion.V26_3)
                .stream()
                .anyMatch(boundary -> !observed.atLeast(boundary) && client.atLeast(boundary));
    }

    /**
     * A finish_configuration generation: received contents, the tags they reset, the client-effective tag
     * reloads and the world registries built from them.
     */
    public record StagedConfiguration(
            Map<String, List<RegistryEntry>> received,
            Map<String, RegistryTags.Payload> reset,
            Map<String, RegistryTags.Payload> effective,
            ClientWorldRegistries.Generation world) {}

    /**
     * Builds this configuration's generation without publishing any of it. {@code bound} carries configuration
     * tags an older wire binds only at finish; null when it has none.
     */
    public StagedConfiguration stageConfiguration(
            ProtocolVersion source, ProtocolVersion observed, ProtocolVersion client, RegistryTags bound) {
        var pendingTags = configurationTags;
        if (bound != null) {
            pendingTags = new LinkedHashMap<>(pendingTags == null ? Map.of() : pendingTags);
            pendingTags.putAll(bound.tags());
        }
        boolean worldContentsReceived = configurationRegistries != null;
        boolean originalClientHasRegistryData =
                configurationRegistries != null || viaAppendsRegistryData(observed, client);
        var received = receivedRegistries;
        var reset = new LinkedHashMap<String, RegistryTags.Payload>();
        if (configurationRegistries != null) {
            // ContentsCollector creates a new remote biome registry; omitted or whole-empty
            // tag packets do not carry its previous registry's memberships into that level.
            if (configurationRegistries.containsKey("minecraft:worldgen/biome"))
                reset.put("minecraft:worldgen/biome", RegistryTags.Payload.EMPTY);
            if (configurationRegistries.containsKey(ClientWorldRegistries.PAINTINGS))
                reset.put(ClientWorldRegistries.PAINTINGS, RegistryTags.Payload.EMPTY);
            received = Map.copyOf(configurationRegistries);
        }
        if (pendingTags == null)
            return new StagedConfiguration(worldContentsReceived ? received : null, reset, null, null);
        var effective = new LinkedHashMap<String, RegistryTags.Payload>();
        pendingTags.forEach((registry, payload) -> {
            // RegistryDataCollector's contents branch skips whole-empty static
            // tag reloads. Its tags-only branch applies them, including clears.
            // A named tag with [] is nonempty and always replaces its binding.
            if (!originalClientHasRegistryData || !clientPayloadIsEmpty(registry, payload, source, client))
                effective.put(registry, payload);
        });
        var world = worldRegistries == null
                ? null
                : worldRegistries.stage(received, observed, effective, worldContentsReceived);
        return new StagedConfiguration(worldContentsReceived ? received : null, reset, effective, world);
    }

    /** Applies a staged generation; nothing here can fail. */
    public void publish(StagedConfiguration staged, ProtocolVersion observed) {
        if (staged.received() != null) {
            tags.putAll(staged.reset());
            receivedRegistries = staged.received();
            receivedRegistryVersion = observed;
            configurationRegistries = null;
            simulationDefaults = null;
            simulationRegistries = null;
            simulationActions = null;
            simulatorTags.clear(); // Dynamic biome tag IDs may name different entries in the new contents.
        }
        if (staged.effective() == null) return;
        if (staged.world() != null) worldRegistries.publish(staged.world());
        appendTags(new RegistryTags(staged.effective()), false);
        configurationTags = null;
    }

    /** Model defaults after a generation failed to stage: no received contents and no received tags. */
    public void fallback() {
        var received =
                configurationRegistries != null && configurationRegistries.containsKey(ClientWorldRegistries.DIMENSIONS)
                        ? configurationRegistries.get(ClientWorldRegistries.DIMENSIONS)
                        : receivedRegistries.get(ClientWorldRegistries.DIMENSIONS);
        // A repeated failure keeps the client's dimension order from the published generation.
        List<String> dimensions = received != null
                ? received.stream().map(RegistryEntry::name).toList()
                : worldRegistries == null
                        ? null
                        : worldRegistries.snapshot().registries().get(ClientWorldRegistries.DIMENSIONS).stream()
                                .map(ClientWorldRegistries.Definition::key)
                                .toList();
        tags.clear();
        geometryTags = null;
        actionTags.clear();
        lastClientTags = null;
        simulatorTags.clear();
        configurationTags = null;
        configurationRegistries = null;
        receivedRegistries = Map.of();
        receivedRegistryVersion = null;
        simulationDefaults = null;
        simulationRegistries = null;
        simulationActions = null;
        if (worldRegistries != null) worldRegistries.fallback(dimensions);
    }

    static boolean clientPayloadIsEmpty(
            String registry, RegistryTags.Payload payload, ProtocolVersion source, ProtocolVersion client) {
        // Untranslated tags project to a copy with one entry per payload entry.
        if (source == client && payload != null) return payload.isEmpty();
        return projectedPayloadIsEmpty(registry, payload, source, client);
    }

    static boolean projectedPayloadIsEmpty(
            String registry, RegistryTags.Payload payload, ProtocolVersion source, ProtocolVersion client) {
        Map<String, List<String>> named;
        if (registry.equals("minecraft:block")) named = exportTags("minecraft:block", payload);
        else if (registry.equals("minecraft:item")) named = exportTags("minecraft:item", payload);
        else if (registry.equals("minecraft:fluid")) named = exportTags("minecraft:fluid", payload);
        else if (registry.equals("minecraft:entity_type")) named = exportTags("minecraft:entity_type", payload);
        else return payload.isEmpty();
        // Via can add a tag even to a whole-empty native payload. The original
        // client's collector observes that projected map, not native emptiness.
        return ProtocolCodecs.projectTags(registry, named, source, client, client)
                .isEmpty();
    }

    public void appendTags(RegistryTags packet) {
        appendTags(packet, true);
    }

    private void appendTags(RegistryTags packet, boolean updateWorld) {
        if (updateWorld && worldRegistries != null) packet.tags().forEach(worldRegistries::applyTags);
        if (packet.tags().entrySet().stream()
                .allMatch(entry -> samePayload(entry.getValue(), tags.get(entry.getKey())))) return;
        tags.putAll(packet.tags());
        geometryTags = null;
        actionTags.clear();
        lastClientTags = null;
        simulatorTags.clear();
    }

    private static boolean samePayload(RegistryTags.Payload current, RegistryTags.Payload previous) {
        if (previous == null) return false;
        // Reordering the same native map changes the winner of a valid Via tag
        // rename collision, so order participates in this received generation.
        return current.entries().entrySet().stream()
                .toList()
                .equals(previous.entries().entrySet().stream().toList());
    }

    public Tags geometryTags() {
        if (geometryTags == null)
            geometryTags = new Tags(
                    exportTags("minecraft:block", tags.get("minecraft:block")),
                    exportTags("minecraft:item", tags.get("minecraft:item")),
                    exportTags("minecraft:fluid", tags.get("minecraft:fluid")),
                    exportTags("minecraft:entity_type", tags.get("minecraft:entity_type")));
        return geometryTags;
    }

    /** Server keys, including empty memberships, precede the vendor's exact generated tags. */
    public Tags actionTags(ProtocolVersion source, ProtocolVersion client, ProtocolVersion model) {
        return actionTags.computeIfAbsent(
                new ActionPath(source, client, model), key -> project(geometryTags(), source, client, model));
    }

    public HolderSets.Overlay blockSimulatorTags(ProtocolVersion source, ProtocolVersion client, DataTables defaults) {
        var path = new ActionPath(source, client, ProtocolVersion.V26_3);
        return simulatorTags.computeIfAbsent(path, ignored -> {
            var projected = actionTags(source, client, ProtocolVersion.V26_3);
            var received = new HashMap<String, Set<String>>();
            projected.blocks().forEach((key, members) -> received.put("block:" + key, Set.copyOf(members)));
            projected.items().forEach((key, members) -> received.put("item:" + key, Set.copyOf(members)));
            projected.fluids().forEach((key, members) -> received.put("fluid:" + key, Set.copyOf(members)));
            projected.entities().forEach((key, members) -> received.put("entity_type:" + key, Set.copyOf(members)));
            var biomes =
                    worldRegistries(() -> ClientWorldRegistries.modelDefaults()).snapshot();
            var biomePayload = tags.get("minecraft:worldgen/biome");
            var biomeTags = biomePayload == null
                    ? biomes.tags().getOrDefault("minecraft:worldgen/biome", Map.of())
                    : exportTags(
                            biomes.registries().get("minecraft:worldgen/biome").stream()
                                    .map(ClientWorldRegistries.Definition::key)
                                    .toList(),
                            biomePayload);
            biomeTags.forEach((key, members) -> received.put("worldgen/biome:" + key, Set.copyOf(members)));
            // A payload replaces this registry's complete tag set. Missing names have no
            // membership after MappedRegistry.PendingTags.apply refreshes its holders.
            for (String registry : List.of("block", "item", "fluid", "entity_type", "worldgen/biome"))
                if (tags.containsKey("minecraft:" + registry))
                    defaults.tags().keySet().stream()
                            .filter(key -> key.startsWith(registry + ":"))
                            .forEach(key -> received.putIfAbsent(key, Set.of()));
            return HolderSets.Overlay.differingFrom(defaults, received);
        });
    }

    /** Movement membership stays in the original client's domain, without a return trip to the host. */
    public Tags clientTags(ProtocolVersion source, ProtocolVersion client) {
        if (lastClientTags == null || lastClientTagsSource != source || lastClientTagsClient != client) {
            lastClientTags = actionTags(source, client, client);
            lastClientTagsSource = source;
            lastClientTagsClient = client;
        }
        return lastClientTags;
    }

    private static Map<String, List<String>> exportTags(String registry, RegistryTags.Payload payload) {
        if (payload != null) return exportTags(ClientWorldDefaults.defaults().initialNames(registry), payload);
        String prefix = registry.substring("minecraft:".length()) + ":";
        var result = new LinkedHashMap<String, List<String>>();
        DataTables.defaults().tags().forEach((key, members) -> {
            if (key.startsWith(prefix)) result.put(key.substring(prefix.length()), List.copyOf(members));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> exportTags(List<String> names, RegistryTags.Payload payload) {
        var result = new LinkedHashMap<String, List<String>>();
        payload.entries()
                .forEach((name, ids) -> result.put(
                        name,
                        ids.stream()
                                .filter(id -> id >= 0 && id < names.size())
                                .map(names::get)
                                .toList()));
        return Collections.unmodifiableMap(result);
    }

    private static Tags project(Tags tags, ProtocolVersion source, ProtocolVersion client, ProtocolVersion model) {
        return new Tags(
                ProtocolCodecs.projectTags("minecraft:block", tags.blocks(), source, client, model),
                ProtocolCodecs.projectTags("minecraft:item", tags.items(), source, client, model),
                ProtocolCodecs.projectTags("minecraft:fluid", tags.fluids(), source, client, model),
                ProtocolCodecs.projectTags("minecraft:entity_type", tags.entities(), source, client, model));
    }

    public record Tags(
            Map<String, List<String>> blocks,
            Map<String, List<String>> items,
            Map<String, List<String>> fluids,
            Map<String, List<String>> entities) {
        public Tags {
            blocks = copy(blocks);
            items = copy(items);
            fluids = copy(fluids);
            entities = copy(entities);
        }

        private static Map<String, List<String>> copy(Map<String, List<String>> source) {
            var copy = new LinkedHashMap<String, List<String>>();
            source.forEach((key, members) -> copy.put(key, List.copyOf(members)));
            return Collections.unmodifiableMap(copy);
        }
    }
}
