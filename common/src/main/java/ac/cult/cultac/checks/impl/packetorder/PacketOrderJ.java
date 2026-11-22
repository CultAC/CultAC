package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

@CheckData(
        name = "PacketOrderJ",
        stableKey = "cult.packetorder.attack_interact_use_order",
        description = "Sent use item after attacking without the expected interaction packet",
        experimental = true)
public class PacketOrderJ extends Check implements CheckListener {
    private final ClientVisibilityProcessor visibility;

    public PacketOrderJ(final CultPlayer player, final ClientVisibilityProcessor visibility) {
        super(player);
        this.visibility = visibility;
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        if (attackedWithoutInteraction(player)) onMissingInteraction(event, player);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (attackedWithoutInteraction(player) && interactionExpected(player)) onMissingInteraction(event, player);
    }

    // we don't check stabbing here because you don't need to target an entity to stab
    private static boolean attackedWithoutInteraction(CultPlayer player) {
        return player.packetOrderProcessor.isAttacking() && !player.packetOrderProcessor.isInteracting();
    }

    private void onMissingInteraction(PacketReceiveEvent event, CultPlayer player) {
        if (flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    // Minecraft#startUseItem skips MultiPlayerGameMode#interact for a picked entity outside
    // Player#isWithinEntityInteractionRange and falls through to MultiPlayerGameMode#useItem.
    private boolean interactionExpected(CultPlayer player) {
        if (player.isBedrockMovement()) return true;
        var target = player.compensatedEntities.getEntity(player.packetOrderProcessor.getAttackTarget());
        return target != null && visibility.entityWithinInteractionRange(target);
    }
}
