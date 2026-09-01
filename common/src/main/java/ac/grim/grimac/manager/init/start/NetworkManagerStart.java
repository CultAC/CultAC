package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.GrimAPI;

public class NetworkManagerStart implements StartableInitable {
    @Override
    public void start() {
        GrimAPI.INSTANCE.getNetworkManager().start(GrimAPI.INSTANCE.getPlugin());
    }
}
