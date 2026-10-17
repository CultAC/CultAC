package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;

@CheckData(
        name = "PacketOrderA",
        stableKey = "cult.packetorder.window_click_order",
        description = "Sent pickup and quick-move inventory clicks in an invalid order",
        experimental = true)
public class PacketOrderA extends Check implements CheckListener {
    public PacketOrderA(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        final WindowClickType clickType = packet.clickType();

        if ((clickType == WindowClickType.PICKUP || clickType == WindowClickType.PICKUP_ALL)
                        && player.packetOrderProcessor.isQuickMoveClicking()
                || clickType == WindowClickType.QUICK_MOVE && player.packetOrderProcessor.isPickUpClicking()) {
            if (flag() && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
