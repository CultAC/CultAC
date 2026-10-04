package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryNames;
import ac.cult.cultac.utils.minecraft.MinecraftRegistries;
import ac.cult.cultac.utils.minecraft.NativeGeometryTags;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;

/**
 * Received block, item, fluid and entity type memberships at the compensated boundary.
 * Isolated interactions receive these exact named tag snapshots with their item components.
 */
public final class ClientComponentRegistries {
    private final Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags =
            new HashMap<>();
    private ac.cult.placement.api.GeometryTags geometryTags;
    private final Map<ModelRegistryNames, ac.cult.placement.api.GeometryTags> modelTags =
            new java.util.IdentityHashMap<>();

    private record NativePath(ModelRegistryNames names, ProtocolVersion source, ProtocolVersion target) {}

    private final Map<NativePath, ac.cult.placement.api.GeometryTags> nativeModelTags = new HashMap<>();

    private record ActionPath(ProtocolVersion source, ProtocolVersion client, ProtocolVersion model) {}

    private final Map<ActionPath, ac.cult.placement.api.GeometryTags> actionTags = new HashMap<>();
    private Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> configurationTags;
    private boolean observedRegistryData;

    /** Configuration collects replacements before applying them to the inherited bindings. */
    public void beginConfiguration() {
        configurationTags = new java.util.LinkedHashMap<>();
    }

    public void appendConfigurationTags(ac.cult.cultac.network.packet.RegistryTags packet) {
        if (configurationTags == null) beginConfiguration();
        configurationTags.putAll(packet.tags());
    }

    /** The observed stream sent registry_data in the current configuration. */
    public void observeRegistryData() {
        observedRegistryData = true;
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
        boolean originalClientHasRegistryData = observedRegistryData || viaAppendsRegistryData(observed, client);
        observedRegistryData = false;
        if (configurationTags == null) return;
        var effective = new java.util.LinkedHashMap<
                ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload>();
        configurationTags.forEach((registry, payload) -> {
            // RegistryDataCollector's contents branch skips whole-empty static
            // tag reloads. Its tags-only branch applies them, including clears.
            // A named tag with [] is nonempty and always replaces its binding.
            if (!originalClientHasRegistryData || !clientPayloadIsEmpty(registry, payload, source, client))
                effective.put(registry, payload);
        });
        appendTags(new ac.cult.cultac.network.packet.RegistryTags(effective));
        configurationTags = null;
    }

    private static boolean clientPayloadIsEmpty(
            ResourceKey<? extends Registry<?>> registry,
            TagNetworkSerialization.NetworkPayload payload,
            ProtocolVersion source,
            ProtocolVersion client) {
        Map<String, List<String>> named;
        if (registry == net.minecraft.core.registries.Registries.BLOCK)
            named = exportTags(net.minecraft.core.registries.BuiltInRegistries.BLOCK, payload);
        else if (registry == net.minecraft.core.registries.Registries.ITEM)
            named = exportTags(net.minecraft.core.registries.BuiltInRegistries.ITEM, payload);
        else if (registry == net.minecraft.core.registries.Registries.FLUID)
            named = exportTags(net.minecraft.core.registries.BuiltInRegistries.FLUID, payload);
        else if (registry == net.minecraft.core.registries.Registries.ENTITY_TYPE)
            named = exportTags(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE, payload);
        else return payload.isEmpty();
        // Via can add a tag even to a whole-empty native payload. The original
        // client's collector observes that projected map, not native emptiness.
        return ac.cult.cultac.protocol.ProtocolCodecs.projectTags(
                        NmsIdentifierUtil.resourceKey(registry), named, source, client, client)
                .isEmpty();
    }

    public void appendTags(ac.cult.cultac.network.packet.RegistryTags packet) {
        if (packet.tags().entrySet().stream()
                .allMatch(entry -> samePayload(entry.getValue(), tags.get(entry.getKey())))) return;
        tags.putAll(packet.tags());
        geometryTags = null;
        modelTags.clear();
        nativeModelTags.clear();
        actionTags.clear();
    }

    private static boolean samePayload(
            TagNetworkSerialization.NetworkPayload current, TagNetworkSerialization.NetworkPayload previous) {
        if (previous == null) return false;
        // Reordering the same native map changes the winner of a valid Via tag
        // rename collision, so order participates in this received generation.
        return ac.cult.cultac.network.codec.NativeTagOrder.entries(current).entrySet().stream()
                .toList()
                .equals(ac.cult.cultac.network.codec.NativeTagOrder.entries(previous).entrySet().stream()
                        .toList());
    }

    public ac.cult.placement.api.GeometryTags geometryTags(MinecraftRegistries context) {
        if (geometryTags == null)
            geometryTags = new ac.cult.placement.api.GeometryTags(
                    exportTags(
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                            tags.get(net.minecraft.core.registries.Registries.BLOCK)),
                    exportTags(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM,
                            tags.get(net.minecraft.core.registries.Registries.ITEM)),
                    exportTags(
                            net.minecraft.core.registries.BuiltInRegistries.FLUID,
                            tags.get(net.minecraft.core.registries.Registries.FLUID)),
                    exportTags(
                            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE,
                            tags.get(net.minecraft.core.registries.Registries.ENTITY_TYPE)));
        return geometryTags;
    }

    /** One snapshot per model and received generation; tag names and empty memberships stay intact. */
    public ac.cult.placement.api.GeometryTags geometryTags(MinecraftRegistries context, ModelRegistryNames names) {
        return modelTags.computeIfAbsent(names, key -> NativeGeometryTags.translate(geometryTags(context), key));
    }

    /** Primary geometry/Bedrock actions retain entity tags with explicit native/model versions. */
    public ac.cult.placement.api.GeometryTags geometryTags(
            MinecraftRegistries context, ModelRegistryNames names, ProtocolVersion source, ProtocolVersion target) {
        return nativeModelTags.computeIfAbsent(
                new NativePath(names, source, target),
                key -> NativeGeometryTags.translate(geometryTags(context), names, source, target));
    }

    /** Server keys, including empty memberships, precede the vendor's exact generated tags. */
    public ac.cult.placement.api.GeometryTags actionTags(
            MinecraftRegistries context, ProtocolVersion source, ProtocolVersion client, ProtocolVersion model) {
        return actionTags.computeIfAbsent(
                new ActionPath(source, client, model),
                key -> NativeGeometryTags.project(geometryTags(context), source, client, model));
    }

    /** Movement membership stays in the original client's domain, without a return trip to the host. */
    public ac.cult.placement.api.GeometryTags clientTags(
            MinecraftRegistries context, ProtocolVersion source, ProtocolVersion client) {
        return actionTags(context, source, client, client);
    }

    private static <T> Map<String, List<String>> exportTags(
            Registry<T> registry, TagNetworkSerialization.NetworkPayload payload) {
        var result = new java.util.LinkedHashMap<String, List<String>>();
        if (payload == null)
            registry.getTags()
                    .forEach(tag -> result.put(
                            NmsIdentifierUtil.resourceKey(tag.key()),
                            tag.stream()
                                    .map(holder -> NmsIdentifierUtil.registryKey(registry, holder.value()))
                                    .toList()));
        else
            ac.cult.cultac.network.codec.NativeTagOrder.entries(payload)
                    .forEach((name, ids) -> result.put(
                            NmsIdentifierUtil.asString(name),
                            ids.intStream()
                                    .mapToObj(registry::get)
                                    .flatMap(java.util.Optional::stream)
                                    .map(holder -> NmsIdentifierUtil.registryKey(registry, holder.value()))
                                    .toList()));
        return java.util.Collections.unmodifiableMap(result);
    }
}
