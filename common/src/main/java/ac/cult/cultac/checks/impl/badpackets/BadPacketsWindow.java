package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.latency.CompensatedOpenWindow;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "BadPacketsWindow",
        stableKey = "cult.badpackets.wrong_window",
        description = "Clicked in an unopen window")
public class BadPacketsWindow extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("windowId={sint}");

    public BadPacketsWindow(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        int windowId = packet.windowId();
        if (windowId == 0 && !player.openWindow.mustBeOpen()) return;
        for (CompensatedOpenWindow.Window window : player.openWindow.getPossibilities()) {
            if (window != null && window.id() == windowId) return;
        }
        if (flag(V.write(verbose()).sint(windowId)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.closeInventorySafely();
        }
    }
}
