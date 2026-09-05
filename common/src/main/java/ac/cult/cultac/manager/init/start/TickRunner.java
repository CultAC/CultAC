package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.utils.anticheat.LogUtil;

public class TickRunner implements StartableInitable {
    @Override
    public void start() {
        LogUtil.info("Registering tick schedulers...");

        if (CultAPI.INSTANCE.getPlatform() == Platform.FOLIA) {
            CultAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(CultAPI.INSTANCE.getGrimPlugin(), () -> {
                CultAPI.INSTANCE.getTickManager().tickSync();
                CultAPI.INSTANCE.getTickManager().tickAsync();
            }, 1, 1);
        } else {
            CultAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().runAtFixedRate(CultAPI.INSTANCE.getGrimPlugin(), () -> CultAPI.INSTANCE.getTickManager().tickSync(), 0, 1);
            CultAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(CultAPI.INSTANCE.getGrimPlugin(), () -> CultAPI.INSTANCE.getTickManager().tickAsync(), 0, 1);
        }
    }
}
