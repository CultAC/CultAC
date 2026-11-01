package ac.cult.cultac.platform.bukkit.scheduler.bukkit;

import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.scheduler.EntityScheduler;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.grim.grimac.api.plugin.GrimPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BukkitEntityScheduler implements EntityScheduler {
    private final BukkitScheduler scheduler = Bukkit.getScheduler();

    @Override
    public void execute(
            @NotNull CultEntity entity,
            @NotNull GrimPlugin plugin,
            @NotNull Runnable run,
            @Nullable Runnable retired,
            long delay) {
        final Runnable guarded_run = RuntimeEnvironment.guard(run);
        final Runnable guarded_retired = RuntimeEnvironment.guard(retired);

        scheduler.runTaskLater(CultACBukkitLoaderPlugin.LOADER, guarded_run, delay);
    }

    @Override
    public TaskHandle run(
            @NotNull CultEntity entity,
            @NotNull GrimPlugin plugin,
            @NotNull Runnable task,
            @Nullable Runnable retired) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);
        final Runnable guarded_retired = RuntimeEnvironment.guard(retired);

        return new BukkitTaskHandle(scheduler.runTask(CultACBukkitLoaderPlugin.LOADER, guarded_task));
    }

    @Override
    public TaskHandle runDelayed(
            @NotNull CultEntity entity,
            @NotNull GrimPlugin plugin,
            @NotNull Runnable task,
            @Nullable Runnable retired,
            long delayTicks) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);
        final Runnable guarded_retired = RuntimeEnvironment.guard(retired);

        return new BukkitTaskHandle(scheduler.runTaskLater(CultACBukkitLoaderPlugin.LOADER, guarded_task, delayTicks));
    }

    @Override
    public TaskHandle runAtFixedRate(
            @NotNull CultEntity entity,
            @NotNull GrimPlugin plugin,
            @NotNull Runnable task,
            @Nullable Runnable retired,
            long initialDelayTicks,
            long periodTicks) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);
        final Runnable guarded_retired = RuntimeEnvironment.guard(retired);

        return BukkitTaskHandle.repeating(
                scheduler.runTaskTimer(CultACBukkitLoaderPlugin.LOADER, guarded_task, initialDelayTicks, periodTicks));
    }
}
