package ac.cult.cultac.manager.init.stop;

import ac.cult.cultac.CultAPI;

public class TerminateNetworkManager implements StoppableInitable {
    @Override
    public void stop() {
        CultAPI.INSTANCE.getNetworkManager().stop();
    }
}
