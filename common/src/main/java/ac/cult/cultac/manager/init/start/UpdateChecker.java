package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.commands.CultVersion;

public class UpdateChecker implements StartableInitable {
    @Override
    public void start() {
        CultVersion.checkForUpdatesAsync(CultAPI.INSTANCE.getPlatformServer().getConsoleSender());
    }
}
