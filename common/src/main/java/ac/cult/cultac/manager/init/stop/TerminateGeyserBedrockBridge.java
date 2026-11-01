package ac.cult.cultac.manager.init.stop;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.utils.anticheat.LogUtil;

public class TerminateGeyserBedrockBridge implements StoppableInitable {
    @Override
    public void stop() {
        var plugins = CultAPI.INSTANCE.getPluginManager();
        if (plugins.getPlugin("Geyser-Spigot") == null && plugins.getPlugin("Geyser-Velocity") == null) return;
        try {
            GeyserBedrockBridgeRuntime.stop();
        } catch (LinkageError error) {
            LogUtil.warn("Unable to stop the Geyser Bedrock movement bridge cleanly: "
                    + error.getClass().getSimpleName());
        }
    }
}
