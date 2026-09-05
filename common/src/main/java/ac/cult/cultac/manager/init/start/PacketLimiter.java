package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.player.CultPlayer;

public class PacketLimiter implements StartableInitable {
    @Override
    public void start() {
        CultAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(CultAPI.INSTANCE.getGrimPlugin(), () -> {
            for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
                // Avoid concurrent reading on an integer as it's results are unknown
                player.cancelledPackets.set(0);
            }
        }, 1, 20);
    }
}
