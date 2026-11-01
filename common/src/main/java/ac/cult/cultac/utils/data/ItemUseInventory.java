package ac.cult.cultac.utils.data;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.Inventory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Compensated, client-visible stacks used by both use prediction and metadata callbacks. */
public final class ItemUseInventory {
    private ItemUseInventory() {}

    public static List<SimItemStack> hotbar(CultPlayer player) {
        var result = new ArrayList<SimItemStack>(9);
        var storage = player.getInventory().inventory.getInventoryStorage();
        for (int slot = 0; slot < 9; slot++) {
            SimItemStack stack = storage.getItem(Inventory.HOTBAR_OFFSET + slot);
            result.add(stack == null ? SimItemStack.EMPTY : stack);
        }
        return result;
    }

    public static ClientItemUseState.Snapshot prepare(CultPlayer player) {
        var cached = player.clientItemUse.preparedAt(player.packetStateData.acceptedClientTick);
        if (cached != null) return cached;
        return player.clientItemUse.prepare(
                player.packetStateData.acceptedClientTick,
                player.lastTransactionReceived.get(),
                player.getClientVersion(),
                player.getInventory().getHandItem(Hand.MAIN_HAND),
                player.getInventory().getHandItem(Hand.OFF_HAND),
                hotbar(player));
    }

    /**
     * A use the definite state accepts may still be refused in a client reality that an
     * unproven completion left: a shared cooldown, an emptied or converted stack, or full food.
     * Any hotbar stack can be the main-hand selection while a hotbar change is unconfirmed.
     */
    public static boolean possiblyRefused(CultPlayer player, Hand hand) {
        var state = player.clientItemUse;
        var hotbar = hotbar(player);
        var off = player.getInventory().getHandItem(Hand.OFF_HAND);
        state.retainPossiblyChanged(hotbar, off);
        var cooldowns = player.checkManager.getCompensatedCooldown();
        var stacks = hand == Hand.OFF_HAND
                ? List.of(off)
                : player.attackStrength.hasUnconfirmedHotbarChange()
                        ? hotbar
                        : List.of(player.getInventory().getHandItem(Hand.MAIN_HAND));
        for (SimItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (state.possiblyEmpty(stack) || cooldowns.possiblyHasItem(stack)) return true;
            var food = ItemComponents.food(stack.components());
            if (state.possiblyFull() && food != null && !food.canAlwaysEat() && !player.isInvulnerable) return true;
            if (projectileWeapon(stack) && possiblyChangedAmmo(state, hotbar, off, stack.is("minecraft:crossbow")))
                return true;
        }
        return false;
    }

    private static boolean projectileWeapon(SimItemStack stack) {
        return stack.is("minecraft:bow") || stack.is("minecraft:crossbow");
    }

    /** Player.getProjectile reads ammo from any slot; completion stamps exist only on hotbar and offhand stacks. */
    private static boolean possiblyChangedAmmo(
            ClientItemUseState state, List<SimItemStack> hotbar, SimItemStack off, boolean crossbow) {
        Set<String> arrows = DataTables.defaults().tags().getOrDefault("item:minecraft:arrows", Set.of());
        for (SimItemStack stack : hotbar) {
            if (possiblyChangedAmmo(state, stack, arrows, crossbow)) return true;
        }
        return possiblyChangedAmmo(state, off, arrows, crossbow);
    }

    private static boolean possiblyChangedAmmo(
            ClientItemUseState state, SimItemStack stack, Set<String> arrows, boolean crossbow) {
        return !stack.isEmpty()
                && state.possiblyEmpty(stack)
                && (arrows.contains(stack.itemKey()) || crossbow && stack.is("minecraft:firework_rocket"));
    }

    public static void metadata(CultPlayer player, byte flags) {
        player.clientItemUse.serverFlags(
                (flags & 1) != 0,
                (flags & 2) != 0 ? Hand.OFF_HAND : Hand.MAIN_HAND,
                player.getInventory().getHandItem(Hand.MAIN_HAND),
                player.getInventory().getHandItem(Hand.OFF_HAND),
                hotbar(player));
    }
}
