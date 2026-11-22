package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;

@CheckData(
        name = "MultiActionsP",
        stableKey = "cult.multiactions.inventory_drop",
        description = "Dropping items without using click packets while in an inventory",
        experimental = true)
public class MultiActionsP extends Check implements CheckListener {
    public MultiActionsP(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onDrop(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (isDrop(packet.action()) && player.openWindow.mustBeOpen() && flag() && shouldModifyPackets()) {
            if (canCancelInventoryAction(packet.action())) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            player.closeInventorySafely();
        }
    }
}
