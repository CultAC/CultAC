package ac.cult.cultac.utils.inventory.inventory;

public enum WindowClickType {
    PICKUP,
    QUICK_MOVE,
    SWAP,
    CLONE,
    THROW,
    QUICK_CRAFT,
    PICKUP_ALL;

    public static final WindowClickType[] VALUES = values();

    /** ContainerInput's pinned stream codec uses PICKUP for an out-of-range ID. */
    public static WindowClickType byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : PICKUP;
    }
}
