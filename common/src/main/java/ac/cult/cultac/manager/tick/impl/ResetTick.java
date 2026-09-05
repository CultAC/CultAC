package ac.cult.cultac.manager.tick.impl;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.tick.Tickable;
import ac.cult.cultac.player.CultPlayer;

public class ResetTick implements Tickable {
    @Override
    public void tick() {
        for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            player.packetEntityReplication.tickStartTick();
        }
    }
}
