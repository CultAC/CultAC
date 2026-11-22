package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.FormerName;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.checks.type.LegacyPacketEventSemantics;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;
import ac.cult.cultac.utils.inventory.InventoryClick;

@CheckData(
        name = "InventoryD",
        formerNames = @FormerName(name = "MultiActionsS", until = 2),
        stableKey = "cult.multiactions.inventory_swing",
        description = "Swinging in an inventory",
        experimental = true)
public class MultiActionsS extends Check implements DecodedPacketReceiveListener {
    public MultiActionsS(CultPlayer player) {
        super(player);
    }

    private boolean flagAction() {
        return InventoryActions.reportedTogether(this)
                ? flag(InventoryActions.V.write(verbose(), InventoryActions.ACTION_SWING))
                : flag();
    }

    private boolean swing;

    @Override
    public void onDecodedPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ServerboundSwing) {
            // ClientPacketListener#handleAnimate swings whatever screen is open.
            if (player.serverSwingEcho.isEcho()) return;
            if (swing) flagAction();
            if (player.openWindow.mustBeOpen()) {
                swing = true;
                return;
            }
            if (player.openWindow.mustBeOpen() && flagAction() && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
                player.closeInventorySafely();
            }
        } else if (swing && !LegacyPacketEventSemantics.isAsync(event)) {
            swing = false;
            if (!(event.getPacket() instanceof InventoryClick click) || !isDropClick(click)) flagAction();
        }
    }

    private static boolean isDropClick(InventoryClick click) {
        return switch (click.clickType()) {
            case PICKUP, QUICK_MOVE -> click.slot() == -999;
            case THROW -> true;
            default -> false;
        };
    }
}
