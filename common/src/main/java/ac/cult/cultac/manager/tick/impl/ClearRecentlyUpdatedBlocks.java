package ac.cult.cultac.manager.tick.impl;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.tick.Tickable;
import ac.cult.cultac.player.CultPlayer;

public class ClearRecentlyUpdatedBlocks implements Tickable {

    private static final int maxTickAge = 2;

    @Override
    public void tick() {
        for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            player.blockHistory.cleanup(CultAPI.INSTANCE.getTickManager().currentTick - maxTickAge);
        }
    }
}
