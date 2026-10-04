package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;

@CheckData(name = "SelfInteract", stableKey = "cult.badpackets.self_hit", description = "Interacted with self")
public class SelfInteract extends Check implements CheckListener {
    public SelfInteract(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onInteractEntity(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(
                player.getClientVersion(), player.getObservedProtocol())) return;
        onInteract(event, packet.entityId());
    }

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(
                player.getClientVersion(), player.getObservedProtocol())) return;
        packet.target().ifPresent(entityId -> onInteract(event, entityId));
    }

    // TODO: should check for camera entity id instead of player entity id?
    private void onInteract(PacketReceiveEvent event, int entityId) {
        if (player.cameraEntity.isSelf()
                && entityId == player.entityID
                && flag()
                && shouldModifyPackets()) { // Instant ban
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
