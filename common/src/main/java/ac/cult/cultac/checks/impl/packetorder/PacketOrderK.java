package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "PacketOrderK",
        stableKey = "cult.packetorder.inventory_open_order",
        description = "Opened, clicked, or closed inventory in the wrong packet order",
        experimental = true)
public class PacketOrderK extends Check implements CheckListener {
    // Shape index == KIND_* constant value.
    private static final Verbose V =
            Verbose.of("open, clicking={bool}, closing={bool}").or("click").or("close");

    static final int KIND_OPEN = 0;
    static final int KIND_CLICK = 1;
    static final int KIND_CLOSE = 2;

    public PacketOrderK(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        onClickOrClose(event, player, KIND_CLICK);
    }

    @CultPacketHandler
    public void onContainerClose(
            PacketReceiveEvent<ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose> event,
            CultPlayer player,
            ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose packet) {
        onClickOrClose(event, player, KIND_CLOSE);
    }

    private void onClickOrClose(PacketReceiveEvent event, CultPlayer player, int kind) {
        if (player.packetOrderProcessor.isOpeningInventory()) {
            if (flag(V.write(verbose(), kind)) && shouldModifyPackets() && kind == KIND_CLICK) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
