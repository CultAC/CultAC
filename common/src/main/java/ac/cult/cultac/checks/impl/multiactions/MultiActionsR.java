package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.FormerName;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.InventoryClick;

@CheckData(
        name = "InventoryJ",
        formerNames = @FormerName(name = "MultiActionsR", until = 2),
        stableKey = "cult.multiactions.inventory_portal",
        description = "Clicking in inventory while in a nether portal",
        experimental = true)
public class MultiActionsR extends Check implements CheckListener {
    public MultiActionsR(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onAction(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        boolean portalClosedScreen = player.isBedrockMovement()
                ? player.intersectedWithNetherPortal
                : player.localPortalProcess.closedScreen();
        if (portalClosedScreen && player.openWindow.mustBeOpen() && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.closeInventorySafely();
        }
    }
}
