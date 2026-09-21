package ac.cult.cultac.utils.inventory;

import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import java.util.Map;
import java.util.function.BiPredicate;
import org.bukkit.inventory.ItemStack;

/** A menu action and its claimed results, independent of the transport packet. */
public record InventoryClick(int windowId, int stateId, int slot, int button, WindowClickType clickType,
                             Map<Integer, ItemStack> changedSlots, ItemStack carriedItem,
                             BiPredicate<Integer, ItemStack> matches) {
    public InventoryClick {
        changedSlots = Map.copyOf(changedSlots);
        java.util.Objects.requireNonNull(matches);
    }

    public InventoryClick(int windowId, int stateId, int slot, int button, WindowClickType clickType,
                          Map<Integer, ItemStack> changedSlots, ItemStack carriedItem) {
        this(windowId, stateId, slot, button, clickType, changedSlots, carriedItem, (index, item) -> true);
    }
}
