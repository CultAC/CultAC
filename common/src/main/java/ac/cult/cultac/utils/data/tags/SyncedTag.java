package ac.cult.cultac.utils.data.tags;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class SyncedTag<T> {

    private final Identifier location;
    private final Set<T> values;
    private final boolean supported;

    private SyncedTag(Identifier location, Set<T> defaultValues, boolean supported) {
        this.location = location;
        this.supported = supported;
        this.values = Collections.newSetFromMap(new IdentityHashMap<>());
        this.values.addAll(defaultValues);
    }

    public static <T> Builder<T> builder(Identifier location) {
        return new Builder<>(location);
    }

    public Identifier location() {
        return location;
    }

    public boolean contains(T value) {
        return values.contains(value);
    }


    public void readTagValues(Collection<? extends Holder<T>> entries) {
        if (!supported) return;

        // Server is sending tag replacement, clear default values.
        values.clear();
        for (Holder<T> entry : entries) {
            values.add(entry.value());
        }
    }

    public static final class Builder<T> {
        private final Identifier location;
        private Set<T> defaultValues;
        private boolean supported = true;

        private Builder(Identifier location) {
            this.location = location;
        }

        public Builder<T> supported(boolean supported) {
            this.supported = supported;
            return this;
        }

        public Builder<T> defaults(Set<T> defaultValues) {
            this.defaultValues = defaultValues;
            return this;
        }

        public SyncedTag<T> build() {
            return new SyncedTag<>(location, defaultValues, supported);
        }
    }
}
