package ac.cult.cultac.companion.apollo;

import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.companion.bridge.CompanionBridge;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reports the players' Lunar Client mods to the proxy when Apollo-Bukkit is installed. No Apollo class loads unless it
 * is: {@link ApolloMods} holds every reference to Apollo's API.
 */
public final class ApolloModule {
    static final String PLUGIN = "Apollo-Bukkit";

    private static ApolloMods mods;

    private ApolloModule() {}

    public static void enable(JavaPlugin plugin, CompanionBridge bridge) {
        // Every plugin is loaded before this STARTUP plugin enables, so an installed Apollo is already known.
        if (!bridge.isEnabled() || Bukkit.getPluginManager().getPlugin(PLUGIN) == null) return;
        bridge.addCapabilities(ServerCapability.APOLLO_MODS.flag());
        // Apollo enables after this plugin; the first tick follows every plugin's enable and precedes any join.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
                plugin.getLogger().warning(PLUGIN + " did not enable; Lunar Client mods will not reach the proxy");
                return;
            }
            try {
                ApolloMods created = new ApolloMods(bridge, plugin.getLogger());
                created.register();
                mods = created;
            } catch (LinkageError | RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Unable to read Lunar Client mods from " + PLUGIN, failure);
            }
        });
    }

    public static void disable() {
        if (mods == null) return;
        mods.unregister();
        mods = null;
    }
}
