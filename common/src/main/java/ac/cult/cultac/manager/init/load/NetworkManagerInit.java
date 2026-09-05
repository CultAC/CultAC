package ac.cult.cultac.manager.init.load;

import ac.cult.cultac.CultAPI;

public class NetworkManagerInit implements LoadableInitable {
    @Override
    public void load() {
        CultAPI.INSTANCE.getNetworkManager().load(CultAPI.INSTANCE.getPlugin());
    }
}
