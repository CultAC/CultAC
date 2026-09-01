package ac.grim.grimac.manager.init.stop;

import ac.grim.grimac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.grim.grimac.utils.anticheat.LogUtil;

public class TerminateGeyserBedrockBridge implements StoppableInitable {
    @Override
    public void stop() {
        try {
            GeyserBedrockBridgeRuntime.stop();
        } catch (LinkageError error) {
            LogUtil.warn("Unable to stop the Geyser Bedrock movement bridge cleanly: " + error.getClass().getSimpleName());
        }
    }
}
