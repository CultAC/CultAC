package ac.cult.cultac.vanilla;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;

/** Immutable tag contents, so equivalent sessions can share one pending binding. */
final class VanillaTagBindings<T> implements Registry.PendingTags<T> {
    private final Registry<T> registry;
    private final Map<TagKey<T>, List<Holder<T>>> contents;
    private final Registry.PendingTags<T> pending;
    private final int hash;

    VanillaTagBindings(Registry<T> registry, TagLoader.LoadResult<T> tags) {
        this.registry = registry;
        Map<TagKey<T>, List<Holder<T>>> copied = new HashMap<>();
        tags.tags().forEach((key, values) -> copied.put(key, List.copyOf(values)));
        contents = Map.copyOf(copied);
        hash = 31 * System.identityHashCode(registry) + contents.hashCode();
        pending = registry.prepareTagReload(new TagLoader.LoadResult<>(registry.key(), contents));
    }

    @Override
    public ResourceKey<? extends Registry<? extends T>> key() {
        return pending.key();
    }

    @Override
    public HolderLookup.RegistryLookup<T> lookup() {
        return pending.lookup();
    }

    @Override
    public int size() {
        return pending.size();
    }

    @Override
    public void apply() {
        throw new UnsupportedOperationException("Connection tags are read through VanillaContext");
    }

    Registry<T> registry() {
        return registry;
    }

    Map<TagKey<T>, List<Holder<T>>> contents() {
        return contents;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof VanillaTagBindings<?> that
                        && registry == that.registry
                        && contents.equals(that.contents);
    }
}
