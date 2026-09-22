package ac.cult.cultac.network.protocol.util;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class FoliaCompatUtil {
    private FoliaCompatUtil() {
    }

    public static void runTaskForEntity(Entity entity, Plugin plugin, Runnable runnable, Runnable retired, long delay) {
        if (entity == null || plugin == null || runnable == null) {
            return;
        }

        // Available throughout Paper 1.21.2+, including Folia
        entity.getScheduler().execute(plugin, runnable, retired, delay);
    }
}
