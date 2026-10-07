package ac.cult.cultac.platform.api.player;

import ac.cult.blocksim.engine.SimItemStack;

public interface PlatformInventory {
    SimItemStack getStack(int bukkitSlot, int vanillaSlot);

    SimItemStack getMainHand();

    SimItemStack getOffHand();
}
