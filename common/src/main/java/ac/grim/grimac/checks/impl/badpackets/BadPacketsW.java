package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;

@CheckData(name = "BadPacketsW", stableKey = "grim.badpackets.invalid_entity_target", description = "Interacted with non-existent entity", experimental = true)
public class BadPacketsW extends Check implements CheckListener {
    public BadPacketsW(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onInteract(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        validateTarget(event, NmsPacketUtil.readInteract(packet));
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        validateTarget(event, NmsPacketUtil.readAttack(packet));
    }

    @GrimPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, GrimPlayer player, ServerboundSpectatorActionPacket packet) {
        packet.spectateEntityId().ifPresent(entityId -> {
            if (isInvalidEntityTarget(entityId)) {
                handleInvalidTarget(event, entityId);
            }
        });
    }

    public void handleInvalidTarget(PacketReceiveEvent event, int entityId) {
        if (flag("entityId=" + entityId) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    private void validateTarget(PacketReceiveEvent event, NmsPacketUtil.InteractData interact) {
        if (interact != null && isInvalidEntityTarget(interact.entityId())) {
            handleInvalidTarget(event, interact.entityId());
        }
    }

    private boolean isInvalidEntityTarget(int entityId) {
        if (player.compensatedEntities.entityMap.containsKey(entityId)
                || player.compensatedEntities.serverPositionsMap.containsKey(entityId)) {
            return false;
        }

        // Pre-1.14 clients may still target an entity removed during this server
        // transaction because their ray-trace entity list updates later.
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14)
                || !player.packetEntityReplication.wasDespawnedThisTransaction(entityId);
    }
}
