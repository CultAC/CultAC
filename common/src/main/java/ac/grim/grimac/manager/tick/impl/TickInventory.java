package ac.grim.grimac.manager.tick.impl;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.tick.Tickable;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.lists.CorrectingPlayerInventoryStorage;

public class TickInventory implements Tickable {
    @Override
    public void tick() {
        for (GrimPlayer player : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {

            if (player.getInventory().inventory.getInventoryStorage() instanceof CorrectingPlayerInventoryStorage correcting) {
                correcting.tickWithBukkit();
            }
        }
    }
}
