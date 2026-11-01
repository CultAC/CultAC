package ac.cult.cultac.platform.bukkit.scheduler.folia;

import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.scheduler.EntityScheduler;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.platform.bukkit.entity.BukkitCultEntity;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.host.Callbacks;
import ac.grim.grimac.api.plugin.GrimPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FoliaEntityScheduler implements EntityScheduler {

    @Override
    public void execute(
            @NotNull CultEntity entity,
            @NotNull GrimPlugin plugin,
            @NotNull Runnable task,
            @Nullable Runnable retired,
            long delay) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);
        final Runnable guarded_retired = RuntimeEnvironment.guard(retired);

        ((BukkitCultEntity) entity)
                .getBukkitEntity()
                .getScheduler()
                .execute(CultACBukkitLoaderPlugin.LOADER, guarded_task, guarded_retired, delay);
    }

    @Override
    public TaskHandle run(
            @NotNull CultEntity entity,
            @NotNull GrimPlugin plugin,
            @NotNull Runnable task,
            @Nullable Runnable retired) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);
        final Runnable guarded_retired = RuntimeEnvironment.guard(retired);

        ScheduledTask scheduled = ((BukkitCultEntity) entity)
                .getBukkitEntity()
                .getScheduler()
                .run(CultACBukkitLoaderPlugin.LOADER, Callbacks.run(guarded_task), guarded_retired);

        return scheduled == null ? null : new FoliaTaskHandle(scheduled);
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

        ScheduledTask scheduled = ((BukkitCultEntity) entity)
                .getBukkitEntity()
                .getScheduler()
                .runDelayed(CultACBukkitLoaderPlugin.LOADER, Callbacks.run(guarded_task), guarded_retired, delayTicks);

        return scheduled == null ? null : new FoliaTaskHandle(scheduled);
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

        ScheduledTask scheduled = ((BukkitCultEntity) entity)
                .getBukkitEntity()
                .getScheduler()
                .runAtFixedRate(
                        CultACBukkitLoaderPlugin.LOADER,
                        Callbacks.run(guarded_task),
                        guarded_retired,
                        initialDelayTicks,
                        periodTicks);

        return scheduled == null ? null : new FoliaTaskHandle(scheduled);
    }
}
