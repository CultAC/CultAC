package ac.grim.grimac.manager.init.load;

import ac.grim.grimac.GrimAPI;

public class NetworkManagerInit implements LoadableInitable {
    @Override
    public void load() {
        GrimAPI.INSTANCE.getNetworkManager().load(GrimAPI.INSTANCE.getPlugin());
    }
}
