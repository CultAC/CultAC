package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.FormerName;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

// Minecraft#tick reads the pick key in handleKeybinds, which it runs only while no screen is open; a middle click in a
// container screen is a clone click. Through 1.21.3 a pick sends pick_item only for an item outside the hotbar in
// survival. InBedChatScreen alone sends STOP_SLEEPING, from its Leave Bed button, Escape and onClose, and the tick
// sets it only while no screen is open; a container screen set over it sends nothing, and closing that one sends its
// container_close first.
@CheckData(
        name = "InventoryL",
        formerNames = @FormerName(name = "MultiActionsU", until = 2),
        stableKey = "cult.multiactions.inventory_pick_leave_bed",
        description = "Picked a block or left a bed while in an inventory",
        experimental = true)
public class MultiActionsU extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("action=pickBlock")
            .or("action=pickEntity")
            .or("action=pick")
            .or("action=leaveBed");

    public MultiActionsU(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler("serverbound.pick_item_from_block")
    public void onPickBlock(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (player.openWindow.mustBeOpen() && flag(V.write(verbose(), 0))) cancel(event);
    }

    @CultPacketHandler("serverbound.pick_item_from_entity")
    public void onPickEntity(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (player.openWindow.mustBeOpen() && flag(V.write(verbose(), 1))) cancel(event);
    }

    @CultPacketHandler("serverbound.pick_item")
    public void onPickItem(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (player.openWindow.mustBeOpen() && flag(V.write(verbose(), 2))) cancel(event);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.STOP_SLEEPING
                && player.openWindow.mustBeOpen()
                && flag(V.write(verbose(), 3))) {
            cancel(event);
        }
    }

    private void cancel(PacketReceiveEvent<?> event) {
        if (!shouldModifyPackets()) return;
        event.setCancelled(true);
        player.onPacketCancel();
        player.closeInventorySafely();
    }
}
