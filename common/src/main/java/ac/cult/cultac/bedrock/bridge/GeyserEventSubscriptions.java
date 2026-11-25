package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.host.NativeEventHub;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.geysermc.geyser.api.event.EventRegistrar;

/** Keeps Geyser's event types in its own loader and retires cached callbacks on stop. */
final class GeyserEventSubscriptions implements AutoCloseable {
    private final Object bus;
    private final Object owner;
    private volatile boolean active = true;
    private final List<AutoCloseable> scoped = new ArrayList<>();

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
        var host = RuntimeEnvironment.host();
        if (host != null) {
            var hub = (NativeEventHub)
                    host.shared("geyser-events", () -> new NativeEventHub(bus, EventRegistrar.of(host.plugin())));
            scoped.add(hub.subscribe(host, event, last, value -> guarded.accept(event.cast(value))));
            return;
        }
        try {
            if (last) {
                // Floodgate bundles another PostOrder. A direct reference may resolve to
                // that copy and violate Geyser's method parameter loader constraint.
                Class<?> order = Class.forName(
                        "org.geysermc.event.PostOrder", true, bus.getClass().getClassLoader());
                Method subscribe =
                        bus.getClass().getMethod("subscribe", Object.class, Class.class, Consumer.class, order);
                subscribe.invoke(
                        bus, owner, event, guarded, order.getField("LAST").get(null));
            } else {
                bus.getClass()
                        .getMethod("subscribe", Object.class, Class.class, Consumer.class)
                        .invoke(bus, owner, event, guarded);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to register Geyser event " + event.getSimpleName(), failure);
        }
    }

    @Override
    public void close() {
        active = false;
        if (RuntimeEnvironment.host() != null) {
            for (var subscription : scoped) {
                try {
                    subscription.close();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
            scoped.clear();
            return;
        }
        try {
            bus.getClass().getMethod("unregisterAll", Object.class).invoke(bus, owner);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to remove Geyser event subscriptions", failure);
        }
    }
}
