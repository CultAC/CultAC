package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.player.CultPlayer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.inventory.Inventory;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

/** Applies only changes made while Geyser validates a native action. */
final class GeyserInventoryActions {
    private GeyserInventoryActions() { }

    static void translate(GeyserSession session, CultPlayer player, BedrockPacket packet, Runnable translator) {
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket
                || packet instanceof org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
                || packet instanceof PlayerAuthInputPacket input && input.getItemStackRequest() == null) {
            translator.run();
            return;
        }
        var inventory = session.getPlayerInventory();
        var holder = session.getInventoryHolder();
        var menu = holder == null ? null : holder.inventory();
        ItemStack[] before = snapshot(inventory);
        ItemStack[] menuBefore = menu == null ? null : snapshot(menu);
        ItemStack cursor = inventory.getCursor().copy().getItemStack();
        int selected = inventory.getHeldItemSlot();
        translator.run();
        var target = player.getInventory();
        target.applyBedrockSlots(0, changes(session, before, inventory));
        if (menu != null && session.getInventoryHolder() == holder) {
            target.applyBedrockSlots(menu.getJavaId(), changes(session, menuBefore, menu));
        }
        ItemStack afterCursor = inventory.getCursor().copy().getItemStack();
        if (!Objects.equals(cursor, afterCursor)) target.applyBedrockCursor(convert(session, afterCursor));
        if (selected != inventory.getHeldItemSlot()) {
            int slot = inventory.getHeldItemSlot();
            PacketPlayerDigging.selectHotbarSlot(player, slot);
            player.actionManager.selectHotbarSlot();
            target.selectHotbarSlot(slot);
        }
        if (packet instanceof ContainerClosePacket && session.getInventoryHolder() == null) {
            PacketPlayerWindow.handleInventory(player, false);
            target.closeContainer();
        } else if (packet instanceof ItemStackRequestPacket
                || packet instanceof PlayerAuthInputPacket input && input.getItemStackRequest() != null) {
            PacketPlayerWindow.handleInventory(player, true);
        }
    }

    private static ItemStack[] snapshot(Inventory inventory) {
        ItemStack[] result = new ItemStack[inventory.getSize()];
        for (int slot = 0; slot < result.length; slot++) result[slot] = inventory.getItem(slot).copy().getItemStack();
        return result;
    }

    private static Map<Integer, org.bukkit.inventory.ItemStack> changes(GeyserSession session, ItemStack[] before, Inventory inventory) {
        var changed = new HashMap<Integer, org.bukkit.inventory.ItemStack>();
        for (int slot = 0; slot < before.length; slot++) {
            ItemStack after = inventory.getItem(slot).copy().getItemStack();
            if (!Objects.equals(before[slot], after)) changed.put(slot, convert(session, after));
        }
        return changed;
    }

    private static org.bukkit.inventory.ItemStack convert(GeyserSession session, ItemStack item) {
        return SpigotConversionUtil.fromNmsItemStack(GeyserItemStacks.toServerItem(session, item));
    }
}
