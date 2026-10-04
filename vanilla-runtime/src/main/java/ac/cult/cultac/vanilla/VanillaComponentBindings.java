package ac.cult.cultac.vanilla;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.ResourceKey;

/** Exact defaults for one registry, including the identity of every receiving holder. */
final class VanillaComponentBindings<T> {
    private final ResourceKey<? extends Registry<? extends T>> key;
    private final Map<Holder.Reference<T>, DataComponentMap> contents;
    private final int hash;

    VanillaComponentBindings(DataComponentInitializers.PendingComponents<T> pending) {
        key = pending.key();
        Map<Holder.Reference<T>, DataComponentMap> copied = new HashMap<>();
        pending.forEach(copied::put);
        contents = Map.copyOf(copied);
        hash = 31 * pending.key().hashCode() + contents.hashCode();
    }

    ResourceKey<? extends Registry<? extends T>> key() {
        return key;
    }

    Map<Holder.Reference<T>, DataComponentMap> contents() {
        return contents;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof VanillaComponentBindings<?> that
                        && key().equals(that.key())
                        && contents.equals(that.contents);
    }
}
