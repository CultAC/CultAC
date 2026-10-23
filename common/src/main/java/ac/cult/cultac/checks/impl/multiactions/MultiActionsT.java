package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;

// Minecraft#handleKeybinds reads the hotbar keys and the scroll wheel only while no screen is open; a hotbar key in a
// container screen is a swap click, never a held slot change.
@CheckData(
        name = "MultiActionsT",
        stableKey = "cult.multiactions.inventory_slot",
        description = "Changed the held slot while in an inventory",
        experimental = true)
public class MultiActionsT extends Check implements CheckListener {
    public MultiActionsT(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        if (player.openWindow.mustBeOpen() && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.closeInventorySafely();
        }
    }
}
