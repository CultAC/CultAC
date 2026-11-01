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
        name = "MultiActionsJ",
        stableKey = "cult.multiactions.inventory_use",
        description = "Used an item while in an inventory")
public class MultiActionsJ extends Check implements CheckListener {
    public MultiActionsJ(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onAction(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (player.openWindow.mustBeOpen() && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.closeInventorySafely();
        }
    }
}
