package ac.cult.cultac.platform.bukkit.scheduler.bukkit;

import ac.cult.cultac.platform.api.scheduler.GlobalRegionScheduler;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.grim.grimac.api.plugin.GrimPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.NotNull;

public class BukkitGlobalRegionScheduler implements GlobalRegionScheduler {

    private final BukkitScheduler bukkitScheduler = Bukkit.getScheduler();

    @Override
    public void execute(@NotNull GrimPlugin plugin, @NotNull Runnable task) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        new BukkitTaskHandle(bukkitScheduler.runTask(CultACBukkitLoaderPlugin.LOADER, guarded_task));
    }

    @Override
    public TaskHandle run(@NotNull GrimPlugin plugin, @NotNull Runnable task) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(bukkitScheduler.runTask(CultACBukkitLoaderPlugin.LOADER, guarded_task));
    }

    @Override
    public TaskHandle runDelayed(@NotNull GrimPlugin plugin, @NotNull Runnable task, long delay) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(bukkitScheduler.runTaskLater(CultACBukkitLoaderPlugin.LOADER, guarded_task, delay));
    }

    @Override
    public TaskHandle runAtFixedRate(
            @NotNull GrimPlugin plugin, @NotNull Runnable task, long initialDelayTicks, long periodTicks) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return BukkitTaskHandle.repeating(bukkitScheduler.runTaskTimer(
                CultACBukkitLoaderPlugin.LOADER, guarded_task, initialDelayTicks, periodTicks));
    }

    @Override
    public void cancel(@NotNull GrimPlugin plugin) {
        if (RuntimeEnvironment.host() == null) bukkitScheduler.cancelTasks(CultACBukkitLoaderPlugin.LOADER);
        else RuntimeEnvironment.host().resources().close();
    }
}
