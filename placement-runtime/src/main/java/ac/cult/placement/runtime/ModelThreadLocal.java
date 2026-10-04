package ac.cult.placement.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Model-owned caches avoid retaining retired vanilla classes in host thread locals. */
public final class ModelThreadLocal<T> extends ThreadLocal<T> {
    private static final Set<ModelThreadLocal<?>> CACHES = ConcurrentHashMap.newKeySet();
    private static final Object NULL = new Object();
    private final ConcurrentHashMap<Thread, Object> values = new ConcurrentHashMap<>();
    private final Supplier<? extends T> initial;

    private ModelThreadLocal(Supplier<? extends T> initial) {
        this.initial = Objects.requireNonNull(initial);
        CACHES.add(this);
    }

    public static <T> ThreadLocal<T> withInitial(Supplier<? extends T> initial) {
        return new ModelThreadLocal<>(initial);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        var value = values.computeIfAbsent(Thread.currentThread(), ignored -> {
            T created = initial.get();
            return created == null ? NULL : created;
        });
        return value == NULL ? null : (T) value;
    }

    @Override
    public void set(T value) {
        values.put(Thread.currentThread(), value == null ? NULL : value);
    }

    @Override
    public void remove() {
        values.remove(Thread.currentThread());
    }

    public static void clearAll() {
        CACHES.forEach(cache -> cache.values.clear());
        CACHES.clear();
    }
}
