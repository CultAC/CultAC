package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.commands.CultVersion;

public class UpdateChecker implements StartableInitable {
    @Override
    public void start() {
        if (CultAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("check-for-updates", true)) {
            CultVersion.checkForUpdatesAsync(CultAPI.INSTANCE.getPlatformServer().getConsoleSender());
        }
    }
}
