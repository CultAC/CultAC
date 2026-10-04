package ac.cult.placement.runtime;

import ac.cult.placement.api.GeometryTags;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

/** Request-local tags in the isolated vanilla loader. No live registry bindings are changed. */
public final class RequestTags {
    private static final ThreadLocal<RequestTags> CURRENT = new ThreadLocal<>();
    private static final ConcurrentHashMap<GeometryTags, RequestTags> CACHE = new ConcurrentHashMap<>();
    private final Map<Holder<?>, Set<TagKey<?>>> holders;
    private final Map<TagKey<?>, List<? extends Holder<?>>> members;
    private final Set<ResourceKey<?>> registries;
    private final Map<ResourceKey<?>, HolderLookup.RegistryLookup<?>> lookups;

    private RequestTags(GeometryTags tags) {
        Map<Holder<?>, Set<TagKey<?>>> holders = new HashMap<>();
        Map<TagKey<?>, List<? extends Holder<?>>> members = new HashMap<>();
        var registries = new HashSet<ResourceKey<?>>();
        bind(BuiltInRegistries.BLOCK, tags.blocks(), holders, members, registries);
        bind(BuiltInRegistries.ITEM, tags.items(), holders, members, registries);
        bind(BuiltInRegistries.FLUID, tags.fluids(), holders, members, registries);
        bind(BuiltInRegistries.ENTITY_TYPE, tags.entities(), holders, members, registries);
        holders.replaceAll((holder, values) -> Set.copyOf(values));
        this.holders = Map.copyOf(holders);
        this.members = Map.copyOf(members);
        this.registries = Set.copyOf(registries);
        var lookups = new HashMap<ResourceKey<?>, HolderLookup.RegistryLookup<?>>();
        prepare(BuiltInRegistries.BLOCK, members, lookups);
        prepare(BuiltInRegistries.ITEM, members, lookups);
        prepare(BuiltInRegistries.FLUID, members, lookups);
        prepare(BuiltInRegistries.ENTITY_TYPE, members, lookups);
        this.lookups = Map.copyOf(lookups);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> void prepare(
            Registry<T> registry,
            Map<TagKey<?>, List<? extends Holder<?>>> members,
            Map<ResourceKey<?>, HolderLookup.RegistryLookup<?>> lookups) {
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        members.forEach((key, values) -> {
            if (key.registry().equals(registry.key())) tags.put((TagKey) key, (List) values);
        });
        lookups.put(
                registry.key(),
                registry.prepareTagReload(new net.minecraft.tags.TagLoader.LoadResult<>(registry.key(), tags))
                        .lookup());
    }

    private static <T> void bind(
            Registry<T> registry,
            Map<String, List<String>> tags,
            Map<Holder<?>, Set<TagKey<?>>> holders,
            Map<TagKey<?>, List<? extends Holder<?>>> contents,
            Set<ResourceKey<?>> registries) {
        registries.add(registry.key());
        registry.listElements().forEach(holder -> holders.put(holder, new HashSet<>()));
        tags.forEach((name, values) -> {
            var key = TagKey.create(registry.key(), Identifier.parse(name));
            List<Holder<T>> members = values.stream()
                    .<Holder<T>>map(value -> registry.get(Identifier.parse(value))
                            .orElseThrow(() -> new IllegalArgumentException("Unknown tag member " + value)))
                    .toList();
            contents.put(key, members);
            members.forEach(holder -> holders.get(holder).add(key));
        });
    }

    public static <T> T query(GeometryTags tags, Supplier<T> task) {
        var previous = CURRENT.get();
        if (tags == null) CURRENT.remove();
        else {
            if (CACHE.size() >= 256) CACHE.clear();
            CURRENT.set(CACHE.computeIfAbsent(tags, RequestTags::new));
        }
        try {
            return task.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static Object holderTags(Object holder, Object bound) {
        var context = CURRENT.get();
        var tags = context == null ? null : context.holders.get(holder);
        return tags == null ? bound : tags;
    }

    public static Object holderComponents(Object holder, Object bound) {
        return bound;
    }

    public static Object tagContents(Object named, Object bound) {
        var context = CURRENT.get();
        if (context == null) return bound;
        var key = ((HolderSet.Named<?>) named).key();
        return context.registries.contains(key.registry()) ? context.members.getOrDefault(key, List.of()) : bound;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object registryTag(Object registry, Object tag) {
        var context = CURRENT.get();
        if (context == null) return null;
        var lookup = context.lookups.get(((Registry<?>) registry).key());
        return lookup == null ? null : ((HolderLookup.RegistryLookup) lookup).get((TagKey) tag);
    }

    public static void clear() {
        CACHE.clear();
    }
}
