package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.FormerName;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;

@CheckData(
        name = "InventoryI",
        formerNames = @FormerName(name = "MultiActionsQ", until = 2),
        stableKey = "cult.multiactions.inventory_swap",
        description = "Swapping items with offhand without using click packets while in an inventory",
        experimental = true)
public class MultiActionsQ extends Check implements CheckListener {
    public MultiActionsQ(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSwap(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (packet.action() == PlayerAction.SWAP_ITEM_WITH_OFFHAND
                && player.openWindow.mustBeOpen()
                && flag()
                && shouldModifyPackets()) {
            if (canCancelInventoryAction(packet.action())) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            player.closeInventorySafely();
        }
    }
}
