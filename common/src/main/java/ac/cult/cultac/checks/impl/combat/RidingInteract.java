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
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

/**
 * Minecraft#startAttack and #startUseItem act on the entity LocalPlayer#raycastHitResult selected. Its
 * ProjectileUtil#getEntityHitResult skips an entity that shares the camera's Entity#getRootVehicle (its vehicle, the
 * other riders of its riding tree and its own passengers) unless the eye is inside that entity's box or inside
 * another entity's box before the ray reaches it. A held item's AttackRange pick has no such rule.
 */
@CheckData(
        name = "RidingInteract",
        stableKey = "cult.badpackets.riding_interact",
        description = "Interacted with their vehicle or an entity riding with them",
        experimental = true)
public final class RidingInteract extends Check implements CheckListener {
    private final ClientVisibilityProcessor visibility;

    public RidingInteract(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player);
        this.visibility = visibility;
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(
                player.getClientVersion(), player.getObservedProtocol())) return;
        judge(event, packet.entityId(), packet.action().name());
    }

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        if (!DecodedPacketReliability.interactionFamilyReliable(
                player.getClientVersion(), player.getObservedProtocol())) return;
        packet.target().ifPresent(entityId -> judge(event, entityId, "SPECTATE"));
    }

    private void judge(PacketReceiveEvent<?> event, int entityId, String action) {
        // The pick runs from the camera, and skips the camera's riding tree rather than the player's.
        if (player.isBedrockMovement() || !player.cameraEntity.isSelf()) return;
        // The eye the pick ran from is unknown until the new LocalPlayer ticks and while a teleport is unanswered.
        if (!player.packetStateData.localPlayerTickedSinceSpawn
                || player.getSetbackTeleportUtil().shouldBlockMovement()) return;
        PacketEntity target = player.compensatedEntities.getEntity(entityId);
        if (target == null || !player.compensatedEntities.certainlySharesRootVehicleWithSelf(target)) return;
        if (visibility.ownVehicleTreePickPossible(target)) return;
        if (flag("action=" + action + ", type=" + EntityTypeUtil.getKey(target.type)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
