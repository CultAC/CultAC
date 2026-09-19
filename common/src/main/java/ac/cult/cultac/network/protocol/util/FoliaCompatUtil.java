package ac.cult.cultac.network.protocol.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Runs a task against a specific entity on whichever thread owns that entity.
 *
 * <p>{@link Entity#getScheduler()} is the only supported way to reach an entity from an
 * arbitrary thread. On region-threaded servers (Folia and its forks) it hops onto the thread
 * owning the entity's region; on Paper it runs on the main thread. The legacy
 * {@link org.bukkit.scheduler.BukkitScheduler} rejects tasks outright on region-threaded
 * servers with {@code UnsupportedOperationException: Unsupported in region threading}.</p>
 */
public final class FoliaCompatUtil {
    private FoliaCompatUtil() {
    }

    public static void runTaskForEntity(Entity entity, Plugin plugin, Runnable runnable,
                                        @Nullable Runnable retired, long delay) {
        if (entity == null || plugin == null || runnable == null) {
            return;
        }

        try {
            io.papermc.paper.threadedregions.scheduler.EntityScheduler scheduler = entity.getScheduler();
            if (scheduler != null) {
                // Returns false when the entity is retired; the task is then dropped, which is
                // what every caller here wants (the player this work was meant for is gone).
                scheduler.execute(plugin, runnable, retired, delay);
                return;
            }
        } catch (UnsupportedOperationException | NoSuchMethodError exception) {
            // Server implementation predates the region-threading scheduler API and only has
            // the global scheduler to offer.
        }

        if (delay <= 0) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        }
    }
}
