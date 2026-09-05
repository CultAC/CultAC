package ac.cult.cultac.utils.inventory.inventory;

import net.minecraft.world.inventory.ContainerInput;

public enum WindowClickType {
    PICKUP,
    QUICK_MOVE,
    SWAP,
    CLONE,
    THROW,
    QUICK_CRAFT,
    PICKUP_ALL;

    public static final WindowClickType[] VALUES = values();

    public static WindowClickType fromNms(ContainerInput clickType) {
        return VALUES[clickType.ordinal()];
    }

    public ContainerInput toNms() {
        return ContainerInput.values()[ordinal()];
    }
}
