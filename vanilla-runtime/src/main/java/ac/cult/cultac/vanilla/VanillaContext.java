package ac.cult.cultac.vanilla;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

/** Immutable connection data. Shared vanilla holders retain their bootstrap defaults. */
public final class VanillaContext {
    private static final ThreadLocal<VanillaContext> CURRENT = new ThreadLocal<>();
    private final List<VanillaTagBindings<?>> tags;
    private final Collection<VanillaComponentBindings<?>> components;
    private final Map<Holder<?>, Set<TagKey<?>>> holderTags;
    private final Map<TagKey<?>, List<? extends Holder<?>>> members;
    private final Map<Holder<?>, DataComponentMap> defaults;
    private final Set<ResourceKey<?>> registries;
    private final Map<ResourceKey<?>, HolderLookup.RegistryLookup<?>> lookups;

    VanillaContext(List<VanillaTagBindings<?>> tags, Collection<VanillaComponentBindings<?>> components) {
        this.tags = List.copyOf(tags);
        this.components = List.copyOf(components);
        Map<Holder<?>, Set<TagKey<?>>> holders = new HashMap<>();
        Map<TagKey<?>, List<? extends Holder<?>>> members = new HashMap<>();
        Set<ResourceKey<?>> registries = new HashSet<>();
        for (var binding : tags) {
            registries.add(binding.key());
            binding.registry().listElements().forEach(holder -> holders.put(holder, new HashSet<>()));
            binding.contents().forEach((tag, values) -> {
                members.put(tag, values);
                values.forEach(holder -> holders.computeIfAbsent(holder, ignored -> new HashSet<>())
                        .add(tag));
            });
        }
        holders.replaceAll((holder, values) -> Set.copyOf(values));
        holderTags = Map.copyOf(holders);
        this.members = Map.copyOf(members);
        this.registries = Set.copyOf(registries);
        var lookups = new HashMap<ResourceKey<?>, HolderLookup.RegistryLookup<?>>();
        tags.forEach(binding -> lookups.put(binding.key(), binding.lookup()));
        this.lookups = Map.copyOf(lookups);
        Map<Holder<?>, DataComponentMap> defaults = new HashMap<>();
        components.forEach(binding -> defaults.putAll(binding.contents()));
        this.defaults = Map.copyOf(defaults);
    }

    Collection<VanillaTagBindings<?>> tags() {
        return tags;
    }

    Collection<VanillaComponentBindings<?>> components() {
        return components;
    }

    public static VanillaContext current() {
        return CURRENT.get();
    }

    public static void run(VanillaContext context, Runnable task) {
        var previous = current();
        if (context == null) CURRENT.remove();
        else CURRENT.set(context);
        try {
            task.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** Configuration completion and acknowledged PLAY tags become visible within the current task. */
    static void publish(VanillaContext previous, VanillaContext next) {
        if (current() == previous) CURRENT.set(next);
    }

    // Object signatures keep the class-load transform independent of either vanilla ABI.
    public static Object holderTags(Object holder, Object bound) {
        var context = current();
        var tags = context == null ? null : context.holderTags.get(holder);
        return tags == null ? bound : tags;
    }

    public static Object holderComponents(Object holder, Object bound) {
        var context = current();
        return context == null ? bound : context.defaults.getOrDefault(holder, (DataComponentMap) bound);
    }

    public static Object tagContents(Object named, Object bound) {
        var context = current();
        if (context == null) return bound;
        var tag = ((HolderSet.Named<?>) named).key();
        return context.registries.contains(tag.registry()) ? context.members.getOrDefault(tag, List.of()) : bound;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object registryTag(Object registry, Object tag) {
        var context = current();
        if (context == null) return null;
        var lookup = context.lookups.get(((Registry<?>) registry).key());
        return lookup == null ? null : ((HolderLookup.RegistryLookup) lookup).get((TagKey) tag);
    }
}
