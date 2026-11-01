package ac.cult.cultac.platform.bukkit.scheduler.folia;

import ac.cult.cultac.platform.api.scheduler.AsyncScheduler;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.host.Callbacks;
import ac.grim.grimac.api.plugin.GrimPlugin;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

public class FoliaAsyncScheduler implements AsyncScheduler {

    private final io.papermc.paper.threadedregions.scheduler.AsyncScheduler scheduler = Bukkit.getAsyncScheduler();

    @Override
    public TaskHandle runNow(@NotNull GrimPlugin plugin, @NotNull Runnable task) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return new FoliaTaskHandle(scheduler.runNow(CultACBukkitLoaderPlugin.LOADER, Callbacks.run(guarded_task)));
    }

    @Override
    public TaskHandle runDelayed(
            @NotNull GrimPlugin plugin, @NotNull Runnable task, long delay, @NotNull TimeUnit timeUnit) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return new FoliaTaskHandle(
                scheduler.runDelayed(CultACBukkitLoaderPlugin.LOADER, Callbacks.run(guarded_task), delay, timeUnit));
    }

    @Override
    public TaskHandle runAtFixedRate(
            @NotNull GrimPlugin plugin, @NotNull Runnable task, long delay, long period, @NotNull TimeUnit timeUnit) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return new FoliaTaskHandle(scheduler.runAtFixedRate(
                CultACBukkitLoaderPlugin.LOADER, Callbacks.run(guarded_task), delay, period, timeUnit));
    }

    @Override
    public TaskHandle runAtFixedRate(
            @NotNull GrimPlugin plugin, @NotNull Runnable task, long initialDelayTicks, long periodTicks) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        return new FoliaTaskHandle(scheduler.runAtFixedRate(
                CultACBukkitLoaderPlugin.LOADER,
                Callbacks.run(guarded_task),
                initialDelayTicks * 50,
                periodTicks * 50,
                TimeUnit.MILLISECONDS));
    }

    @Override
    public void cancel(@NotNull GrimPlugin plugin) {
        if (RuntimeEnvironment.host() == null) scheduler.cancelTasks(CultACBukkitLoaderPlugin.LOADER);
        else RuntimeEnvironment.host().resources().close();
    }
}
