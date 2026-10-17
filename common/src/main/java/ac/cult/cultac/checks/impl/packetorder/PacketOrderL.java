package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "PacketOrderL",
        stableKey = "cult.packetorder.drop_item_order",
        description = "Sent drop, inventory open, or offhand swap packets in an invalid order",
        experimental = true)
public class PacketOrderL extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("[inventory|swap]");

    public PacketOrderL(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (packet.action() != PlayerAction.SWAP_ITEM_WITH_OFFHAND) return;

        if (player.packetOrderProcessor.isDropping()) {
            if (flag(V.write(verbose()).bool(false)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
