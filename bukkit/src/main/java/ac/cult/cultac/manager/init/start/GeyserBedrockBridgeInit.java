package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.platform.bukkit.BukkitRuntimeRegistrations;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.utils.anticheat.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

public class GeyserBedrockBridgeInit implements StartableInitable, Listener {
    @Override
    public void start() {
        BukkitRuntimeRegistrations.registerEvents(this, CultACBukkitLoaderPlugin.LOADER);
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

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if ("Geyser-Spigot".equals(event.getPlugin().getName())) {
            try {
                GeyserBedrockBridgeRuntime.stop();
                CultAPI.INSTANCE.getNetworkManager().setPacketOwnerResolver(channel -> null);
            } catch (LinkageError error) {
                LogUtil.warn("Unable to stop the Geyser Bedrock bridge: "
                        + error.getClass().getSimpleName());
            }
        }
    }

    private void startBridge() {
        try {
            GeyserBedrockBridgeRuntime.start();
            // Paper's packets are owned by I/O, except Bedrock sessions on Geyser's tick loop.
            if (GeyserBedrockBridgeRuntime.isRunning()) {
                CultAPI.INSTANCE.getNetworkManager().setPacketOwnerResolver(GeyserBedrockBridgeRuntime::packetOwner);
            }
        } catch (LinkageError error) {
            LogUtil.warn("Unable to start the Geyser Bedrock movement bridge: "
                    + error.getClass().getSimpleName());
        }
    }
}
