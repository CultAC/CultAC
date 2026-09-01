package ac.grim.grimac.manager.init.stop;

import ac.grim.grimac.GrimAPI;

public class TerminateNetworkManager implements StoppableInitable {
    @Override
    public void stop() {
        GrimAPI.INSTANCE.getNetworkManager().stop();
    }
}
