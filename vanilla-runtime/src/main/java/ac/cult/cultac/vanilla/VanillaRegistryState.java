package ac.cult.cultac.vanilla;

import ac.cult.cultac.utils.minecraft.MinecraftRegistries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagNetworkSerialization;

/** Configuration collector confined to the connection owner; publishes immutable registry read contexts. */
public final class VanillaRegistryState {
    private final VanillaBootstrap model;
    private final Map<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>> entries =
            new HashMap<>();
    private final Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags =
            new HashMap<>();
    private RegistryAccess.Frozen registries;
    private volatile VanillaContext bindings;
    private final MinecraftRegistries context;

    VanillaRegistryState(VanillaBootstrap model, VanillaContext bindings) {
        this.model = model;
        this.registries = model.registries();
        this.bindings = bindings;
        this.context = new MinecraftRegistries(() -> registries, model::resources);
    }

    public MinecraftRegistries registries() {
        return context;
    }

    public VanillaContext context() {
        return bindings;
    }

    public void execute(Runnable task) {
        VanillaContext.run(bindings, task);
    }

    public void append(
            ResourceKey<? extends Registry<?>> key, List<RegistrySynchronization.PackedRegistryEntry> values) {
        entries.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(values);
    }

    public void appendTags(Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> values) {
        tags.putAll(values);
    }

    /** Older wire registries use model defaults by name, except for client-visible dimensions. */
    public void finishOlder() {
        var base = model.registries();
        var dimensions = entries.get(Registries.DIMENSION_TYPE);
        if (dimensions != null) {
            var received = RegistryDataLoader.load(
                            Map.of(
                                    Registries.DIMENSION_TYPE,
                                    new RegistryDataLoader.NetworkedRegistryData(
                                            List.copyOf(dimensions), TagNetworkSerialization.NetworkPayload.EMPTY)),
                            model.resources(),
                            base.listRegistries().toList(),
                            RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream()
                                    .filter(data -> data.key().equals(Registries.DIMENSION_TYPE))
                                    .toList(),
                            Runnable::run)
                    .join();
            registries = new RegistryAccess.ImmutableRegistryAccess(Stream.concat(
                            base.registries().filter(entry -> !entry.key().equals(Registries.DIMENSION_TYPE)),
                            received.registries()))
                    .freeze();
        } else registries = base;
        List<VanillaTagBindings<?>> pending = new ArrayList<>();
        VanillaContext.run(null, () -> pending.addAll(snapshotTags(model, registries)));
        var replacements = new HashMap<ResourceKey<? extends Registry<?>>, VanillaTagBindings<?>>();
        tags.forEach((key, payload) -> {
            if (!payload.isEmpty())
                registries.lookup(key).ifPresent(registry -> replacements.put(key, prepare(registry, payload)));
        });
        pending.replaceAll(previous -> replacements.getOrDefault(previous.key(), previous));
        publish(new VanillaContext(pending, snapshotComponents(model, registries)));
        entries.clear();
        tags.clear();
    }

    /** PLAY applies even empty tag payloads, unlike the configuration collector. */
    public Runnable preparePlayTags(
            Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> values) {
        Map<ResourceKey<? extends Registry<?>>, VanillaTagBindings<?>> incoming = new HashMap<>();
        values.forEach((key, payload) -> incoming.put(key, prepare(registries.lookupOrThrow(key), payload)));
        return () -> {
            Map<ResourceKey<? extends Registry<?>>, VanillaTagBindings<?>> updated = new HashMap<>();
            bindings.tags().forEach(previous -> updated.put(previous.key(), previous));
            updated.putAll(incoming);
            publish(new VanillaContext(List.copyOf(updated.values()), bindings.components()));
        };
    }

    /** Matches RegistryDataCollector: resolve tags, load received registries, then initialize defaults. */
    public void finish() {
        List<VanillaTagBindings<?>> pending = new ArrayList<>(bindings.tags());
        Map<ResourceKey<? extends Registry<?>>, VanillaTagBindings<?>> replacements = new HashMap<>();
        Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.NetworkedRegistryData> network = new HashMap<>();
        entries.forEach((key, values) -> network.put(
                key,
                new RegistryDataLoader.NetworkedRegistryData(
                        List.copyOf(values), TagNetworkSerialization.NetworkPayload.EMPTY)));
        tags.forEach((key, payload) -> {
            if (payload.isEmpty()) return; // The vanilla collector also preserves bindings for empty payloads.
            if (!entries.isEmpty() && RegistrySynchronization.isNetworkable(key)) {
                network.compute(
                        key,
                        (ignored, previous) -> new RegistryDataLoader.NetworkedRegistryData(
                                previous == null ? List.of() : previous.elements(), payload));
            } else {
                replacements.put(key, prepare(registries.lookupOrThrow(key), payload));
            }
        });
        pending.replaceAll(previous -> replacements.getOrDefault(previous.key(), previous));
        if (!entries.isEmpty()) {
            var base = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var staticTags = pending.stream()
                    .filter(value -> base.lookup(value.key()).isPresent())
                    .toList();
            var received = RegistryDataLoader.load(
                            network,
                            model.resources(),
                            TagLoader.buildUpdatedLookups(base, new ArrayList<>(staticTags)),
                            RegistryDataLoader.SYNCHRONIZED_REGISTRIES,
                            Runnable::run)
                    .join();
            registries = new RegistryAccess.ImmutableRegistryAccess(
                            Stream.concat(base.registries(), received.registries()))
                    .freeze();
            pending = staticTags;
        }
        publish(new VanillaContext(List.copyOf(pending), snapshotComponents(model, registries)));
        entries.clear();
        tags.clear();
    }

    private void publish(VanillaContext next) {
        var previous = bindings;
        bindings = next;
        VanillaContext.publish(previous, next);
    }

    static VanillaContext snapshot(VanillaBootstrap model, RegistryAccess access) {
        return new VanillaContext(snapshotTags(model, access), snapshotComponents(model, access));
    }

    private static List<VanillaComponentBindings<?>> snapshotComponents(VanillaBootstrap model, RegistryAccess access) {
        return BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(access).stream()
                .<VanillaComponentBindings<?>>map(
                        pending -> model.canonicalComponents(new VanillaComponentBindings<>(pending)))
                .toList();
    }

    private static List<VanillaTagBindings<?>> snapshotTags(VanillaBootstrap model, RegistryAccess access) {
        List<VanillaTagBindings<?>> result = new ArrayList<>();
        access.registries().forEach(entry -> result.add(model.canonicalTags(snapshotTags(entry.value()))));
        return result;
    }

    private static <T> VanillaTagBindings<T> snapshotTags(Registry<T> registry) {
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        registry.getTags().forEach(tag -> tags.put(tag.key(), tag.stream().toList()));
        return new VanillaTagBindings<>(registry, new TagLoader.LoadResult<>(registry.key(), tags));
    }

    private <T> VanillaTagBindings<?> prepare(Registry<T> registry, TagNetworkSerialization.NetworkPayload payload) {
        return model.canonicalTags(new VanillaTagBindings<>(registry, payload.resolve(registry)));
    }
}
