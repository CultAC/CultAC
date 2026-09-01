package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.grim.grimac.utils.anticheat.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

public class GeyserBedrockBridgeInit implements StartableInitable, Listener {
    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, ac.grim.grimac.GrimAPI.INSTANCE.getPlugin());
        if (Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot")) {
            startBridge();
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if ("Geyser-Spigot".equals(event.getPlugin().getName())) {
            startBridge();
        }
    }

    private void startBridge() {
        try {
            GeyserBedrockBridgeRuntime.start();
        } catch (LinkageError error) {
            LogUtil.warn("Unable to start the Geyser Bedrock movement bridge: " + error.getClass().getSimpleName());
        }
    }
}
