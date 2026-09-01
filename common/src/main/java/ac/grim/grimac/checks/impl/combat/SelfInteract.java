package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.DecodedPacketReliability;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;

@CheckData(name = "SelfInteract", stableKey = "grim.badpackets.self_hit", description = "Interacted with self")
public class SelfInteract extends Check implements CheckListener {
    public SelfInteract(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onInteractEntity(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        NmsPacketUtil.InteractData data = NmsPacketUtil.readInteract(packet);
        onInteract(event, data.entityId());
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;
        NmsPacketUtil.InteractData data = NmsPacketUtil.readAttack(packet);
        onInteract(event, data.entityId());
    }


    @GrimPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, GrimPlayer player, ServerboundSpectatorActionPacket packet) {
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
