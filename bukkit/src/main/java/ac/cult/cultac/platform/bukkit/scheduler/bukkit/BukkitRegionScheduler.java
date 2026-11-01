package ac.cult.cultac.platform.bukkit.scheduler.bukkit;

import ac.cult.cultac.platform.api.scheduler.RegionScheduler;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.grim.grimac.api.plugin.GrimPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.NotNull;

public class BukkitRegionScheduler implements RegionScheduler {

    private final BukkitScheduler bukkitScheduler = Bukkit.getScheduler();

    @Override
    public void execute(
            @NotNull GrimPlugin plugin, @NotNull PlatformWorld world, int chunkX, int chunkZ, @NotNull Runnable task) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        new BukkitTaskHandle(bukkitScheduler.runTask(CultACBukkitLoaderPlugin.LOADER, guarded_task));
    }
}
