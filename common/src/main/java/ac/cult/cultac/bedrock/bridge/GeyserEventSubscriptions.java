package ac.cult.cultac.bedrock.bridge;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Keeps Geyser's event types in its own loader and retires cached callbacks on stop. */
final class GeyserEventSubscriptions implements AutoCloseable {
    private final Object bus;
    private final Object owner;
    private volatile boolean active = true;

    GeyserEventSubscriptions(Object bus, Object owner) {
        this.bus = bus;
        this.owner = owner;
    }

    <T> void subscribe(Class<T> event, Consumer<T> listener) {
        subscribe(event, listener, false);
    }

    <T> void subscribeLast(Class<T> event, Consumer<T> listener) {
        subscribe(event, listener, true);
    }

    private <T> void subscribe(Class<T> event, Consumer<T> listener, boolean last) {
        Consumer<T> guarded = value -> {
            if (active) listener.accept(value);
        };
        try {
            if (last) {
                // Floodgate bundles another PostOrder. A direct reference may resolve to
                // that copy and violate Geyser's method parameter loader constraint.
                Class<?> order = Class.forName("org.geysermc.event.PostOrder", true, bus.getClass().getClassLoader());
                Method subscribe = bus.getClass().getMethod("subscribe", Object.class, Class.class, Consumer.class, order);
                subscribe.invoke(bus, owner, event, guarded, order.getField("LAST").get(null));
            } else {
                bus.getClass().getMethod("subscribe", Object.class, Class.class, Consumer.class)
                        .invoke(bus, owner, event, guarded);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to register Geyser event " + event.getSimpleName(), failure);
        }
    }

    @Override public void close() {
        active = false;
        try {
            bus.getClass().getMethod("unregisterAll", Object.class).invoke(bus, owner);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to remove Geyser event subscriptions", failure);
        }
    }
}
