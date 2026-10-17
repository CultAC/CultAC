package ac.cult.cultac.platform.api.manager;

import ac.cult.cultac.platform.api.permissions.PermissionDefaultValue;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Registers permissions only on the main thread, for platforms whose permission registry is not thread-safe.
 * Registrations requested elsewhere wait, in order, for one task handed to {@code toMainThread}.
 */
public final class MainThreadPermissions implements PermissionRegistrationManager {
    private final PermissionRegistrationManager target;
    private final BooleanSupplier onMainThread;
    private final Consumer<Runnable> toMainThread;
    private final Queue<Map.Entry<String, PermissionDefaultValue>> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public MainThreadPermissions(
            PermissionRegistrationManager target, BooleanSupplier onMainThread, Consumer<Runnable> toMainThread) {
        this.target = target;
        this.onMainThread = onMainThread;
        this.toMainThread = toMainThread;
    }

    @Override
    public void registerPermission(String name, PermissionDefaultValue defaultValue) {
        if (onMainThread.getAsBoolean() && pending.isEmpty()) {
            target.registerPermission(name, defaultValue);
            return;
        }
        pending.add(Map.entry(name, defaultValue));
        if (scheduled.compareAndSet(false, true)) toMainThread.accept(this::drain);
    }

    private void drain() {
        scheduled.set(false);
        Map.Entry<String, PermissionDefaultValue> next;
        while ((next = pending.poll()) != null) target.registerPermission(next.getKey(), next.getValue());
    }
}
