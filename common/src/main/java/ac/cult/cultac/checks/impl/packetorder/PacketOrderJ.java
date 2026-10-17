package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;

@CheckData(
        name = "PacketOrderJ",
        stableKey = "cult.packetorder.attack_interact_use_order",
        description = "Sent use item after attacking without the expected interaction packet",
        experimental = true)
public class PacketOrderJ extends Check implements CheckListener {
    public PacketOrderJ(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        onUse(event, player);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        onUse(event, player);
    }

    private void onUse(PacketReceiveEvent event, CultPlayer player) {
        // we don't check stabbing here because you don't need to target an entity to stab
        if (player.packetOrderProcessor.isAttacking() && !player.packetOrderProcessor.isInteracting()) {
            if (flag() && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
