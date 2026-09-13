package ac.cult.cultac.manager.init.stop;

import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.utils.anticheat.LogUtil;

public class TerminateGeyserBedrockBridge implements StoppableInitable {
    @Override
    public void stop() {
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("Geyser-Spigot") == null) return;
        try {
            GeyserBedrockBridgeRuntime.stop();
        } catch (LinkageError error) {
            LogUtil.warn("Unable to stop the Geyser Bedrock movement bridge cleanly: " + error.getClass().getSimpleName());
        }
    }
}
