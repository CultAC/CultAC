package ac.cult.cultac.manager.tick.impl;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.tick.Tickable;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.lists.CorrectingPlayerInventoryStorage;

public class TickInventory implements Tickable {
    @Override
    public void tick() {
        for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {

            if (player.getInventory().inventory.getInventoryStorage() instanceof CorrectingPlayerInventoryStorage correcting) {
                correcting.tickWithBukkit();
            }
        }
    }
}
