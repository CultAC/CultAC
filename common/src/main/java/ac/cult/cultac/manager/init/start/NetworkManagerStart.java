package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;

public class NetworkManagerStart implements StartableInitable {
    @Override
    public void start() {
        CultAPI.INSTANCE.getNetworkManager().start(CultAPI.INSTANCE.getPlugin());
    }
}
