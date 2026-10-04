package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.scheduler.AsyncScheduler;
import ac.cult.cultac.platform.api.scheduler.EntityScheduler;
import ac.cult.cultac.platform.api.scheduler.GlobalRegionScheduler;
import ac.cult.cultac.platform.api.scheduler.PlatformScheduler;
import ac.cult.cultac.platform.api.scheduler.RegionScheduler;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.utils.math.Location;
import ac.grim.grimac.api.plugin.GrimPlugin;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Entity and world work stays on the connection owner; global work is serialized separately. */
final class VelocityScheduler
        implements PlatformScheduler,
                AsyncScheduler,
                GlobalRegionScheduler,
                EntityScheduler,
                RegionScheduler,
                AutoCloseable {
    private final EventExecutor global =
            new DefaultEventExecutor((ThreadFactory) runnable -> new Thread(runnable, "CultAC-global"));
    private final Map<GrimPlugin, Set<Future<?>>> tasks = new ConcurrentHashMap<>();
    private final Function<PlatformWorld, ac.cult.cultac.network.CultConnection> worldOwner;
    private boolean closed;

    VelocityScheduler(Function<PlatformWorld, ac.cult.cultac.network.CultConnection> worldOwner) {
        this.worldOwner = worldOwner;
    }

    @Override
    public AsyncScheduler getAsyncScheduler() {
        return this;
    }

    @Override
    public GlobalRegionScheduler getGlobalRegionScheduler() {
        return this;
    }

    @Override
    public EntityScheduler getEntityScheduler() {
        return this;
    }

    @Override
    public RegionScheduler getRegionScheduler() {
        return this;
    }

    private synchronized TaskHandle schedule(
            EventExecutor executor, GrimPlugin plugin, Runnable task, long delay, long period, TimeUnit unit) {
        if (closed) {
            throw new RejectedExecutionException("CultAC scheduler is closed");
        }
        var owned = tasks.computeIfAbsent(plugin, ignored -> ConcurrentHashMap.newKeySet());
        Future<?> future = period > 0
                ? executor.scheduleAtFixedRate(task, Math.max(0, delay), period, unit)
                : executor.schedule(task, Math.max(0, delay), unit);
        owned.add(future);
        future.addListener(ignored -> owned.remove(future));
        return new TaskHandle() {
            @Override
            public boolean isSync() {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return future.isCancelled();
            }

            @Override
            public void cancel() {
                future.cancel(false);
            }
        };
    }

    @Override
    public TaskHandle runNow(GrimPlugin plugin, Runnable task) {
        return schedule(global, plugin, task, 0, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public TaskHandle runDelayed(GrimPlugin plugin, Runnable task, long delay, TimeUnit unit) {
        return schedule(global, plugin, task, delay, 0, unit);
    }

    @Override
    public TaskHandle runAtFixedRate(GrimPlugin plugin, Runnable task, long delay, long period, TimeUnit unit) {
        if (period <= 0) {
            throw new IllegalArgumentException("Non-positive task period");
        }
        return schedule(global, plugin, task, delay, period, unit);
    }

    @Override
    public void execute(GrimPlugin plugin, Runnable task) {
        runNow(plugin, task);
    }

    @Override
    public TaskHandle run(GrimPlugin plugin, Runnable task) {
        return runNow(plugin, task);
    }

    @Override
    public TaskHandle runDelayed(GrimPlugin plugin, Runnable task, long delay) {
        return runDelayed(plugin, task, ticks(delay), TimeUnit.MILLISECONDS);
    }

    @Override
    public TaskHandle runAtFixedRate(GrimPlugin plugin, Runnable task, long delay, long period) {
        return runAtFixedRate(plugin, task, ticks(delay), ticks(period), TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancel(GrimPlugin plugin) {
        var owned = tasks.get(plugin);
        if (owned != null) {
            owned.forEach(future -> future.cancel(false));
        }
    }

    private TaskHandle entity(
            CultEntity entity, GrimPlugin plugin, Runnable task, Runnable retired, long delay, long period) {
        var user = CultAPI.INSTANCE.getNetworkManager().getUser(entity.getUniqueId(), entity.getNative());
        if (user == null || user.getCultConnection().disconnected()) {
            if (retired != null) {
                runNow(plugin, retired);
            }
            return null;
        }
        var connection = user.getCultConnection();
        // Self-cancellation also retires repeating work exactly once.
        var handle = new AtomicReference<TaskHandle>();
        var retiredOnce = new AtomicBoolean();
        Runnable checked = () -> {
            if (connection.disconnected()) {
                TaskHandle scheduled = handle.get();
                if (scheduled != null) {
                    scheduled.cancel();
                }
                if (retiredOnce.compareAndSet(false, true) && retired != null) {
                    retired.run();
                }
            } else {
                task.run();
            }
        };
        TaskHandle scheduled = schedule(
                connection.owner(),
                plugin,
                () -> connection.runInModel(checked),
                ticks(delay),
                ticks(period),
                TimeUnit.MILLISECONDS);
        handle.set(scheduled);
        if (retiredOnce.get()) {
            scheduled.cancel();
        }
        return scheduled;
    }

    @Override
    public void execute(CultEntity entity, GrimPlugin plugin, Runnable task, Runnable retired, long delay) {
        entity(entity, plugin, task, retired, delay, 0);
    }

    @Override
    public TaskHandle run(CultEntity entity, GrimPlugin plugin, Runnable task, Runnable retired) {
        return entity(entity, plugin, task, retired, 0, 0);
    }

    @Override
    public TaskHandle runDelayed(CultEntity entity, GrimPlugin plugin, Runnable task, Runnable retired, long delay) {
        return entity(entity, plugin, task, retired, delay, 0);
    }

    @Override
    public TaskHandle runAtFixedRate(
            CultEntity entity, GrimPlugin plugin, Runnable task, Runnable retired, long delay, long period) {
        if (period <= 0) {
            throw new IllegalArgumentException("Non-positive task period");
        }
        return entity(entity, plugin, task, retired, delay, period);
    }

    @Override
    public void execute(GrimPlugin plugin, PlatformWorld world, int x, int z, Runnable task) {
        run(plugin, world, x, z, task);
    }

    @Override
    public void execute(GrimPlugin plugin, Location location, Runnable task) {
        execute(plugin, location.getWorld(), 0, 0, task);
    }

    @Override
    public TaskHandle run(GrimPlugin plugin, PlatformWorld world, int x, int z, Runnable task) {
        return runDelayed(plugin, world, x, z, task, 0);
    }

    @Override
    public TaskHandle run(GrimPlugin plugin, Location location, Runnable task) {
        return run(plugin, location.getWorld(), 0, 0, task);
    }

    @Override
    public TaskHandle runDelayed(GrimPlugin plugin, PlatformWorld world, int x, int z, Runnable task, long delay) {
        var connection = worldOwner.apply(world);
        return schedule(
                connection.owner(), plugin, () -> connection.runInModel(task), ticks(delay), 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public TaskHandle runDelayed(GrimPlugin plugin, Location location, Runnable task, long delay) {
        return runDelayed(plugin, location.getWorld(), 0, 0, task, delay);
    }

    @Override
    public TaskHandle runAtFixedRate(
            GrimPlugin plugin, PlatformWorld world, int x, int z, Runnable task, long delay, long period) {
        if (period <= 0) {
            throw new IllegalArgumentException("Non-positive task period");
        }
        var connection = worldOwner.apply(world);
        return schedule(
                connection.owner(),
                plugin,
                () -> connection.runInModel(task),
                ticks(delay),
                ticks(period),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public TaskHandle runAtFixedRate(GrimPlugin plugin, Location location, Runnable task, long delay, long period) {
        return runAtFixedRate(plugin, location.getWorld(), 0, 0, task, delay, period);
    }

    private static long ticks(long value) {
        return Math.multiplyExact(value, 50);
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            tasks.values().forEach(owned -> owned.forEach(future -> future.cancel(false)));
        }
        global.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
