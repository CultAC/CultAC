package ac.cult.cultac.platform.bukkit.scheduler.bukkit;

import ac.cult.cultac.platform.api.scheduler.AsyncScheduler;
import ac.cult.cultac.platform.api.scheduler.PlatformScheduler;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.grim.grimac.api.plugin.GrimPlugin;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.NotNull;

public class BukkitAsyncScheduler implements AsyncScheduler {

    private final BukkitScheduler bukkitScheduler = Bukkit.getScheduler();

    @Override
    public TaskHandle runNow(@NotNull GrimPlugin plugin, @NotNull Runnable task) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(
                bukkitScheduler.runTaskAsynchronously(CultACBukkitLoaderPlugin.LOADER, guarded_task));
    }

    @Override
    public TaskHandle runDelayed(
            @NotNull GrimPlugin plugin, @NotNull Runnable task, long delay, @NotNull TimeUnit timeUnit) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(bukkitScheduler.runTaskLaterAsynchronously(
                CultACBukkitLoaderPlugin.LOADER, guarded_task, PlatformScheduler.convertTimeToTicks(delay, timeUnit)));
    }

    @Override
    public TaskHandle runAtFixedRate(
            @NotNull GrimPlugin plugin, @NotNull Runnable task, long delay, long period, @NotNull TimeUnit timeUnit) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return BukkitTaskHandle.repeating(bukkitScheduler.runTaskTimerAsynchronously(
                CultACBukkitLoaderPlugin.LOADER,
                guarded_task,
                PlatformScheduler.convertTimeToTicks(delay, timeUnit),
                PlatformScheduler.convertTimeToTicks(period, timeUnit)));
    }

    @Override
    public TaskHandle runAtFixedRate(
            @NotNull GrimPlugin plugin, @NotNull Runnable task, long initialDelayTicks, long periodTicks) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return BukkitTaskHandle.repeating(bukkitScheduler.runTaskTimerAsynchronously(
                CultACBukkitLoaderPlugin.LOADER, guarded_task, initialDelayTicks, periodTicks));
    }

    @Override
    public void cancel(@NotNull GrimPlugin plugin) {
        if (RuntimeEnvironment.host() == null) bukkitScheduler.cancelTasks(CultACBukkitLoaderPlugin.LOADER);
        else RuntimeEnvironment.host().resources().close();
    }
}
