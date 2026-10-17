package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;

@CheckData(
        name = "MultiActionsN",
        stableKey = "cult.multiactions.inventory_inventory",
        description = "Opened an inventory while in an inventory",
        experimental = true)
public class MultiActionsN extends Check implements CheckListener {
    public MultiActionsN(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onAction(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.OPEN_INVENTORY
                && player.openWindow.getTicksOpen() != 0
                && player.openWindow.mustBeOpen()
                && flag()
                && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
