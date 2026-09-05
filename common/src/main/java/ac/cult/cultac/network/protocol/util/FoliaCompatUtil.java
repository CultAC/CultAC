package ac.cult.cultac.network.protocol.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

public final class FoliaCompatUtil {
    private FoliaCompatUtil() {
    }

    public static void runTaskForEntity(Entity entity, Plugin plugin, Runnable runnable, Object ignored, long delay) {
        if (entity == null || plugin == null || runnable == null) {
            return;
        }

        try {
            Method getScheduler = entity.getClass().getMethod("getScheduler");
            Object scheduler = getScheduler.invoke(entity);
            if (scheduler != null) {
                Method run = scheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class, long.class);
                run.invoke(scheduler, plugin, (Consumer<Object>) task -> runnable.run(), null, delay);
                return;
            }
        } catch (ReflectiveOperationException ignoredException) {
            // Fall back to the global scheduler below.
        }

        if (delay <= 0) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        }
    }
}
