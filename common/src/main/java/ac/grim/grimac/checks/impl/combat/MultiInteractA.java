package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;

import java.util.ArrayList;

@CheckData(name = "MultiInteractA", stableKey = "grim.multiinteract.multiple_targets", description = "Interacted with multiple entities in the same tick", experimental = true)
public class MultiInteractA extends Check implements PostPredictionListener {
    private static final Verbose V =
            Verbose.of("lastEntity={sint}, entity={sint}, lastSneaking={bool}, sneaking={bool}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private final ArrayList<FlagData> flags = new ArrayList<>();
    private int lastEntity;
    private boolean lastSneaking;
    private boolean hasInteracted = false;

    public MultiInteractA(final GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onInteractEntity(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        NmsPacketUtil.InteractData data = NmsPacketUtil.readInteract(packet);
        onInteract(event, data.entityId(), data.sneaking());
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        NmsPacketUtil.InteractData data = NmsPacketUtil.readAttack(packet);
        onInteract(event, data.entityId(), lastSneaking);
    }


    @GrimPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, GrimPlayer player, ServerboundSpectatorActionPacket packet) {
        // PacketEvents exposed an absent 26.2 spectator target as entity id 0.
        // Preserve that decoded-field contract instead of silently dropping the interaction.
        onInteract(event, NmsPacketUtil.readSpectatorEntityId(packet), lastSneaking);
    }

    @GrimPacketHandler
    public void onTeleportToEntity(PacketReceiveEvent event, GrimPlayer player, ServerboundTeleportToEntityPacket packet) {
        if (player.user.getUUID().equals(NmsPacketUtil.readTeleportToEntityUuid(packet))) {
            onInteract(event, player.entityID, lastSneaking);
        }
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            hasInteracted = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        if (!player.cameraEntity.isSelf()
                || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick)) {
            hasInteracted = false;
        }
    }

    private void onInteract(PacketReceiveEvent event, int entity, boolean sneaking) {
        if (!player.cameraEntity.isSelf()) {
            hasInteracted = false;
        }

        if (hasInteracted && (entity != lastEntity || sneaking != lastSneaking)) {
            if (!canSkipTicks()) {
                if (flag(V.write(verbose()).sint(lastEntity).sint(entity).bool(lastSneaking).bool(sneaking)) && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(lastEntity, entity, lastSneaking, sneaking));
            }
        }

        lastEntity = entity;
        lastSneaking = sneaking;
        hasInteracted = true;
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                flag(V.write(verbose()).sint(data.lastEntity()).sint(data.entity()).bool(data.lastSneaking()).bool(data.sneaking()));
            }
        }

        flags.clear();
    }

    private boolean canSkipTicks() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                && !(player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2));
    }

    private record FlagData(int lastEntity, int entity, boolean lastSneaking, boolean sneaking) {}
}
