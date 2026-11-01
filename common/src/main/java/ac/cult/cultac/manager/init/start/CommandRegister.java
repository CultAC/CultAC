package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.platform.api.command.CommandService;
import ac.cult.cultac.utils.anticheat.LogUtil;

public record CommandRegister(CommandService service) implements StartableInitable {

    @Override
    public void start() {
        try {
            if (service != null) {
                service.registerCommands();
                CultAPI.INSTANCE.getExternalAPI().registerVariable("%command%", CloudCommandService.primaryRoot());
            }
        } catch (RuntimeException t) {
            // This is the ultimate safety net. If command registration fails, Cult keeps running.
            LogUtil.error("Failed to register commands! Cult will run without command support.", t);
        }
    }
}
