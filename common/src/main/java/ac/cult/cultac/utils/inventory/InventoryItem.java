package ac.cult.cultac.utils.inventory;

import ac.cult.blocksim.data.DataTables;

/** Immutable item/count facts used by menu conservation and result validation. */
public record InventoryItem(int item, int count) {
    public static final InventoryItem EMPTY = new InventoryItem(0, 0);

    public boolean isEmpty() {
        return item == 0 || count <= 0;
    }

    public InventoryItem withCount(int count) {
        return new InventoryItem(item, count);
    }

    public String key() {
        return DataTables.defaults().items().get(item).key();
    }

    public boolean is(String key) {
        return key().equals(key);
    }

    public boolean isBlockItem() {
        return !DataTables.defaults().items().get(item).block().isEmpty();
    }
}
