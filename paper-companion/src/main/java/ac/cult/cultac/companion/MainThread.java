package ac.cult.cultac.companion;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Hops tasks onto the server main thread. */
public final class MainThread {
    private MainThread() {}

    /** Runs the task inline on the main thread, else schedules it there; dropped once the plugin is disabled. */
    public static void runMain(Plugin plugin, Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
    }
}
