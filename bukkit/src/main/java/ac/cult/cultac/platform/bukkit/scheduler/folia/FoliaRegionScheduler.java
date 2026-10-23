package ac.cult.cultac.platform.bukkit.scheduler.folia;

import ac.cult.cultac.platform.api.scheduler.RegionScheduler;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.platform.bukkit.world.BukkitPlatformWorld;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.grim.grimac.api.plugin.GrimPlugin;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

public class FoliaRegionScheduler implements RegionScheduler {

    private final io.papermc.paper.threadedregions.scheduler.RegionScheduler regionScheduler =
            Bukkit.getRegionScheduler();

    @Override
    public void execute(
            @NotNull GrimPlugin plugin, @NotNull PlatformWorld world, int chunkX, int chunkZ, @NotNull Runnable task) {
        final Runnable guarded_task = RuntimeEnvironment.guard(task);

        regionScheduler.execute(
                CultACBukkitLoaderPlugin.LOADER,
                ((BukkitPlatformWorld) world).bukkitWorld(),
                chunkX,
                chunkZ,
                guarded_task);
    }
}
