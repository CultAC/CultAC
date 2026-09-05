package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;

@CheckData(name = "SelfInteract", stableKey = "cult.badpackets.self_hit", description = "Interacted with self")
public class SelfInteract extends Check implements CheckListener {
    public SelfInteract(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onInteractEntity(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        NmsPacketUtil.InteractData data = NmsPacketUtil.readInteract(packet);
        onInteract(event, data.entityId());
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        NmsPacketUtil.InteractData data = NmsPacketUtil.readAttack(packet);
        onInteract(event, data.entityId());
    }


    @CultPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, CultPlayer player, ServerboundSpectatorActionPacket packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        packet.spectateEntityId().ifPresent(entityId -> onInteract(event, entityId));
    }

    // TODO: should check for camera entity id instead of player entity id?
    private void onInteract(PacketReceiveEvent event, int entityId) {
        if (player.cameraEntity.isSelf() && entityId == player.entityID
                && flag() && shouldModifyPackets()) { // Instant ban
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
