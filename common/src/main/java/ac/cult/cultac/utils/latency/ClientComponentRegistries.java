package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.ProtocolVersion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Received block, item, fluid and entity type memberships at the compensated boundary.
 * Movement and block actions use immutable named tag snapshots.
 */
public final class ClientComponentRegistries {
    private final Map<String, ac.cult.cultac.network.packet.RegistryTags.Payload> tags = new HashMap<>();
    private Tags geometryTags;

    private record ActionPath(ProtocolVersion source, ProtocolVersion client, ProtocolVersion model) {}

    private final Map<ActionPath, Tags> actionTags = new HashMap<>();
    private final Map<ActionPath, ac.cult.blocksim.data.HolderSets.Overlay> simulatorTags = new HashMap<>();
    private Map<String, ac.cult.cultac.network.packet.RegistryTags.Payload> configurationTags;
    private Map<String, List<ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry>> configurationRegistries;
    private Map<String, List<ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry>> receivedRegistries = Map.of();
    private ProtocolVersion receivedRegistryVersion;
    private ClientWorldRegistries worldRegistries;
    private ac.cult.blocksim.data.DataTables simulationDefaults;
    private SimulationRegistries simulationRegistries;
    private SimulationActions simulationActions;

    public ClientWorldRegistries worldRegistries(java.util.function.Supplier<ClientWorldRegistries.Data> initial) {
        if (worldRegistries == null) {
            worldRegistries = new ClientWorldRegistries(initial.get());
            if (receivedRegistryVersion != null)
                worldRegistries.finish(receivedRegistries, receivedRegistryVersion, tags, true);
            else tags.forEach(worldRegistries::applyTags);
        }
        return worldRegistries;
    }

    public record SimulationRegistries(
            ac.cult.blocksim.data.ItemRegistry items, ac.cult.blocksim.data.InteractionRegistries interactions) {}

    public record SimulationActions(
            ProtocolVersion source,
            ProtocolVersion client,
            java.util.Set<String> features,
            SimulationRegistries registries,
            ac.cult.blocksim.data.HolderSets.Overlay tags,
            ac.cult.cultac.protocol.data.ModelBlockStates states,
            ac.cult.blocksim.BlockSimulator simulator) {}

    /** One immutable binding per received registry/tag/feature generation, reused across actions. */
    public SimulationActions blockSimulatorActions(
            ProtocolVersion source,
            ProtocolVersion client,
            java.util.Set<String> features,
            ac.cult.blocksim.data.DataTables defaults) {
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
                : ac.cult.cultac.protocol.data.ModelBlockStates.project(source, client, ProtocolVersion.V26_3);
        var simulator = ac.cult.blocksim.BlockSimulator.create(
                        defaults.withEnabledFeatures(features),
                        registries.items(),
                        registries.interactions(),
                        ac.cult.cultac.utils.blockplace.BlockSimulatorWorldView::placementUnobstructed,
                        // Living-effect listeners have no field in the existing block-action result contract.
                        new ac.cult.blocksim.behavior.ConsumableBehavior(contextUse -> {}))
                .withTags(tags);
        simulationActions = new SimulationActions(
                source, client, java.util.Set.copyOf(features), registries, tags, states, simulator);
        return simulationActions;
    }

    /** Bundled item defaults and this connection's received interaction definitions. */
    public SimulationRegistries blockSimulatorRegistries(ac.cult.blocksim.data.DataTables defaults) {
        if (simulationRegistries == null || simulationDefaults != defaults) {
            simulationRegistries = new SimulationRegistries(
                    defaults == ac.cult.blocksim.data.DataTables.defaults()
                            ? ac.cult.cultac.utils.inventory.ItemUtil.modelItems()
                            : new ac.cult.blocksim.data.ItemRegistry(defaults),
                    receivedInteractions(ac.cult.blocksim.data.InteractionRegistries.defaults()));
            simulationDefaults = defaults;
        }
        return simulationRegistries;
    }

    private ac.cult.blocksim.data.InteractionRegistries receivedInteractions(
            ac.cult.blocksim.data.InteractionRegistries bindings) {
        // The existing older-model registry producer retains model defaults for these
        // families. Received older NBT stays opaque until its schema is projected.
        if (receivedRegistryVersion != ProtocolVersion.V26_3) return bindings;
        var result = bindings;
        for (String registry :
                List.of("enchantment", "instrument", "jukebox_song", "block_transformer", "block_state_provider")) {
            var entries = receivedRegistries.get("minecraft:" + registry);
            if (entries == null) continue;
            var values = new java.util.LinkedHashMap<String, byte[]>();
            for (var entry : entries) values.put(entry.name(), entry.data());
            result = result.withEncodedRegistry(registry, values);
        }
        return result;
    }

    /** Immutable received memberships; null means this registry has not received a replacement. */
    public ac.cult.cultac.network.packet.RegistryTags.Payload tagPayload(String registry) {
        return tags.get(registry);
    }

    /** Configuration collects replacements before applying them to the inherited bindings. */
    public void beginConfiguration() {
        configurationTags = new java.util.LinkedHashMap<>();
        configurationRegistries = null;
    }

    public void appendConfigurationTags(ac.cult.cultac.network.packet.RegistryTags packet) {
        if (configurationTags == null) beginConfiguration();
        configurationTags.putAll(packet.tags());
    }

    /** RegistryDataCollector.ContentsCollector appends split packets in wire-ID order, including empty registries. */
    public void appendConfigurationRegistryData(ac.cult.cultac.network.packet.RegistryData packet) {
        if (configurationTags == null) beginConfiguration();
        if (configurationRegistries == null) configurationRegistries = new java.util.LinkedHashMap<>();
        var entries = new java.util.ArrayList<>(configurationRegistries.getOrDefault(packet.registry(), List.of()));
        entries.addAll(packet.entries());
        configurationRegistries.put(packet.registry(), List.copyOf(entries));
    }

    /** Published at finish_configuration; tags-only reconfiguration retains the previous contents. */
    public Map<String, List<ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry>> receivedRegistries() {
        return receivedRegistries;
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

    public void finishConfiguration(ProtocolVersion source, ProtocolVersion observed, ProtocolVersion client) {
        boolean worldContentsReceived = configurationRegistries != null;
        boolean originalClientHasRegistryData =
                configurationRegistries != null || viaAppendsRegistryData(observed, client);
        if (configurationRegistries != null) {
            // ContentsCollector creates a new remote biome registry; omitted or whole-empty
            // tag packets do not carry its previous registry's memberships into that level.
            if (configurationRegistries.containsKey("minecraft:worldgen/biome"))
                tags.put("minecraft:worldgen/biome", ac.cult.cultac.network.packet.RegistryTags.Payload.EMPTY);
            if (configurationRegistries.containsKey(ClientWorldRegistries.PAINTINGS))
                tags.put(ClientWorldRegistries.PAINTINGS, ac.cult.cultac.network.packet.RegistryTags.Payload.EMPTY);
            receivedRegistries = Map.copyOf(configurationRegistries);
            receivedRegistryVersion = observed;
            configurationRegistries = null;
            simulationDefaults = null;
            simulationRegistries = null;
            simulationActions = null;
            simulatorTags.clear(); // Dynamic biome tag IDs may name different entries in the new contents.
        }
        if (configurationTags == null) return;
        var effective = new java.util.LinkedHashMap<String, ac.cult.cultac.network.packet.RegistryTags.Payload>();
        configurationTags.forEach((registry, payload) -> {
            // RegistryDataCollector's contents branch skips whole-empty static
            // tag reloads. Its tags-only branch applies them, including clears.
            // A named tag with [] is nonempty and always replaces its binding.
            if (!originalClientHasRegistryData || !clientPayloadIsEmpty(registry, payload, source, client))
                effective.put(registry, payload);
        });
        if (worldRegistries != null)
            worldRegistries.finish(receivedRegistries, observed, effective, worldContentsReceived);
        appendTags(new ac.cult.cultac.network.packet.RegistryTags(effective), false);
        configurationTags = null;
    }

    private static boolean clientPayloadIsEmpty(
            String registry,
            ac.cult.cultac.network.packet.RegistryTags.Payload payload,
            ProtocolVersion source,
            ProtocolVersion client) {
        Map<String, List<String>> named;
        if (registry.equals("minecraft:block")) named = exportTags("minecraft:block", payload);
        else if (registry.equals("minecraft:item")) named = exportTags("minecraft:item", payload);
        else if (registry.equals("minecraft:fluid")) named = exportTags("minecraft:fluid", payload);
        else if (registry.equals("minecraft:entity_type")) named = exportTags("minecraft:entity_type", payload);
        else return payload.isEmpty();
        // Via can add a tag even to a whole-empty native payload. The original
        // client's collector observes that projected map, not native emptiness.
        return ac.cult.cultac.protocol.ProtocolCodecs.projectTags(registry, named, source, client, client)
                .isEmpty();
    }

    public void appendTags(ac.cult.cultac.network.packet.RegistryTags packet) {
        appendTags(packet, true);
    }

    private void appendTags(ac.cult.cultac.network.packet.RegistryTags packet, boolean updateWorld) {
        if (updateWorld && worldRegistries != null) packet.tags().forEach(worldRegistries::applyTags);
        if (packet.tags().entrySet().stream()
                .allMatch(entry -> samePayload(entry.getValue(), tags.get(entry.getKey())))) return;
        tags.putAll(packet.tags());
        geometryTags = null;
        actionTags.clear();
        simulatorTags.clear();
    }

    private static boolean samePayload(
            ac.cult.cultac.network.packet.RegistryTags.Payload current,
            ac.cult.cultac.network.packet.RegistryTags.Payload previous) {
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

    public ac.cult.blocksim.data.HolderSets.Overlay blockSimulatorTags(
            ProtocolVersion source, ProtocolVersion client, ac.cult.blocksim.data.DataTables defaults) {
        var path = new ActionPath(source, client, ProtocolVersion.V26_3);
        return simulatorTags.computeIfAbsent(path, ignored -> {
            var projected = actionTags(source, client, ProtocolVersion.V26_3);
            var received = new HashMap<String, java.util.Set<String>>();
            projected.blocks().forEach((key, members) -> received.put("block:" + key, java.util.Set.copyOf(members)));
            projected.items().forEach((key, members) -> received.put("item:" + key, java.util.Set.copyOf(members)));
            projected.fluids().forEach((key, members) -> received.put("fluid:" + key, java.util.Set.copyOf(members)));
            projected
                    .entities()
                    .forEach((key, members) -> received.put("entity_type:" + key, java.util.Set.copyOf(members)));
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
            biomeTags.forEach((key, members) -> received.put("worldgen/biome:" + key, java.util.Set.copyOf(members)));
            // A payload replaces this registry's complete tag set. Missing names have no
            // membership after MappedRegistry.PendingTags.apply refreshes its holders.
            for (String registry : List.of("block", "item", "fluid", "entity_type", "worldgen/biome"))
                if (tags.containsKey("minecraft:" + registry))
                    defaults.tags().keySet().stream()
                            .filter(key -> key.startsWith(registry + ":"))
                            .forEach(key -> received.putIfAbsent(key, java.util.Set.of()));
            return ac.cult.blocksim.data.HolderSets.Overlay.differingFrom(defaults, received);
        });
    }

    /** Movement membership stays in the original client's domain, without a return trip to the host. */
    public Tags clientTags(ProtocolVersion source, ProtocolVersion client) {
        return actionTags(source, client, client);
    }

    private static Map<String, List<String>> exportTags(
            String registry, ac.cult.cultac.network.packet.RegistryTags.Payload payload) {
        if (payload != null)
            return exportTags(
                    ac.cult.blocksim.environment.ClientWorldDefaults.defaults().initialNames(registry), payload);
        String prefix = registry.substring("minecraft:".length()) + ":";
        var result = new java.util.LinkedHashMap<String, List<String>>();
        ac.cult.blocksim.data.DataTables.defaults().tags().forEach((key, members) -> {
            if (key.startsWith(prefix)) result.put(key.substring(prefix.length()), List.copyOf(members));
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> exportTags(
            List<String> names, ac.cult.cultac.network.packet.RegistryTags.Payload payload) {
        var result = new java.util.LinkedHashMap<String, List<String>>();
        payload.entries()
                .forEach((name, ids) -> result.put(
                        name,
                        ids.stream()
                                .filter(id -> id >= 0 && id < names.size())
                                .map(names::get)
                                .toList()));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Tags project(Tags tags, ProtocolVersion source, ProtocolVersion client, ProtocolVersion model) {
        return new Tags(
                ac.cult.cultac.protocol.ProtocolCodecs.projectTags(
                        "minecraft:block", tags.blocks(), source, client, model),
                ac.cult.cultac.protocol.ProtocolCodecs.projectTags(
                        "minecraft:item", tags.items(), source, client, model),
                ac.cult.cultac.protocol.ProtocolCodecs.projectTags(
                        "minecraft:fluid", tags.fluids(), source, client, model),
                ac.cult.cultac.protocol.ProtocolCodecs.projectTags(
                        "minecraft:entity_type", tags.entities(), source, client, model));
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
            var copy = new java.util.LinkedHashMap<String, List<String>>();
            source.forEach((key, members) -> copy.put(key, List.copyOf(members)));
            return java.util.Collections.unmodifiableMap(copy);
        }
    }
}
