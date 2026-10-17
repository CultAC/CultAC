package ac.cult.cultac.companion;

import ac.cult.cultac.companion.api.ApiModule;
import ac.cult.cultac.companion.bridge.BridgeModule;
import ac.cult.cultac.companion.bridge.CompanionBridge;
import ac.cult.cultac.companion.command.CommandModule;
import java.util.List;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Backend companion for the CultAC Velocity proxy. */
public final class CompanionPlugin extends JavaPlugin {
    private static final List<String> CONFLICTING = List.of("CultAC", "GrimAC");

    private CompanionBridge bridge;

    @Override
    public void onEnable() {
        // Loaded before POSTWORLD plugins enable, so any loaded instance counts as a conflict.
        for (String name : CONFLICTING) {
            Plugin other = getServer().getPluginManager().getPlugin(name);
            if (other != null && other != this) {
                getLogger()
                        .severe(name + " (" + other.getName() + " "
                                + other.getPluginMeta().getVersion()
                                + ") is installed on this server; the CultAC proxy companion replaces it and will not start."
                                + " Remove one of them.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        saveDefaultConfig();
        bridge = BridgeModule.enable(this);
        ApiModule.enable(this, bridge);
        CommandModule.enable(this, bridge);
    }

    @Override
    public void onDisable() {
        if (bridge == null) return;
        CommandModule.disable();
        ApiModule.disable();
        BridgeModule.disable();
        bridge = null;
    }
}
