package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerRotation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;

public class PacketServerTeleport {
    private static final double MOVE_VEHICLE_SNAP_EPSILON = 1.0E-5D;

    // LOW

    @CultPacketHandler
    public void onAcceptTeleportation(
            PacketReceiveEvent<ServerboundAcceptTeleportation> event,
            CultPlayer player,
            ServerboundAcceptTeleportation ack) {
        if (player.isBedrockMovement()) {
            // Geyser's Java acknowledgement does not prove Bedrock processed the teleport.
            return;
        }
        // The wire packet is ID-only through 26.2 and carries position in
        // 26.3. The transport preserves the intercepted endpoint's wire format.
        if (ack.position() == null) {
            return;
        }

        TeleportAcceptData accepted = player.getSetbackTeleportUtil()
                .checkExactJavaTeleportQueue(
                        ack.position().x(), ack.position().y(), ack.position().z(), ack.id());
        if (!accepted.isTeleport()) {
            // In 26.3 Paper applies these coordinates as movement after the ID.
            // A mismatched or transaction-unproven receipt must never reach it.
            event.setCancelled(true);
            return;
        }
        CheckManagerListener.applyTeleportResponse(player, accepted, ack.yaw(), ack.pitch());
    }

    @CultPacketHandler
    public void onPlayerPosition(
            PacketSendEvent<ClientboundPlayerPosition> event, CultPlayer player, ClientboundPlayerPosition packet) {
        if (player.isBedrockMovement()) return;
        handlePlayerPosition(event, player, packet);
    }

    @CultPacketHandler
    public void onPlayerRotation(
            PacketSendEvent<ClientboundPlayerRotation> event, CultPlayer player, ClientboundPlayerRotation packet) {
        if (player.isBedrockMovement()) return;
        handlePlayerRotation(event, player, packet);
    }

    @CultPacketHandler
    public void onMoveVehicle(
            PacketSendEvent<ClientboundMoveVehicle> event, CultPlayer player, ClientboundMoveVehicle packet) {
        if (player.isBedrockMovement()) return;
        handleMoveVehicle(event, player, packet);
    }

    private void handlePlayerPosition(
            PacketSendEvent<ClientboundPlayerPosition> event, CultPlayer player, ClientboundPlayerPosition teleport) {
        Vec3 pos = new Vec3(
                teleport.position().x(),
                teleport.position().y(),
                teleport.position().z());
        Vec3 deltaMovement = new Vec3(
                teleport.delta().x(), teleport.delta().y(), teleport.delta().z());
        RelativeFlag flags = new RelativeFlag(Relative.pack(teleport.relatives()));
        float sourceYaw = player.xRot;
        float sourcePitch = player.yRot;
        float finalYaw = calculateRotation(sourceYaw, teleport.yaw(), flags, RelativeFlag.Y_ROT);
        float finalPitch =
                clamp(calculateRotation(sourcePitch, teleport.pitch(), flags, RelativeFlag.X_ROT), -90.0F, 90.0F);
        boolean initialSpawnTeleport = player.getSetbackTeleportUtil().getRequiredSetBack() == null;

        // This is the first packet sent to the client which we need to track
        if (initialSpawnTeleport) {
            // Player teleport event gets called AFTER player join event
            player.x = pos.x;
            player.y = pos.y;
            player.z = pos.z;
            player.xRot = finalYaw;
            player.yRot = finalPitch;

            player.lastX = pos.x;
            player.lastY = pos.y;
            player.lastZ = pos.z;
            player.lastTickXRot = finalYaw;
            player.lastTickYRot = finalPitch;

            player.pollData();
        }

        // Keep the pre-teleport ping adjacent even inside a replacement packet group.
        // Both the old PosRot and the 26.3 acknowledgement follow its pong.
        CultPlayer.TrackedTransaction proof = player.createTrackedTransactionPacketForDeferredSend();
        if (proof != null) {
            event.getWritesBeforeSend().add(proof.packet());
            event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(proof));
        }
        int lastTransactionSent = proof == null ? player.lastTransactionSent.get() : proof.transaction();
        event.getTasksAfterSend().add(player::sendTransaction);

        player.getSetbackTeleportUtil()
                .addSentTeleport(
                        pos,
                        deltaMovement,
                        lastTransactionSent,
                        flags,
                        true,
                        teleport.teleportId(),
                        sourceYaw,
                        sourcePitch,
                        finalYaw,
                        finalPitch);
    }

    private void handlePlayerRotation(
            PacketSendEvent<ClientboundPlayerRotation> event, CultPlayer player, ClientboundPlayerRotation rotation) {
        RelativeFlag flags = rotationTeleportFlags(rotation);
        float sourceYaw = player.xRot;
        float sourcePitch = player.yRot;
        float finalYaw = calculateRotation(sourceYaw, rotation.yaw(), flags, RelativeFlag.Y_ROT);
        float finalPitch =
                clamp(calculateRotation(sourcePitch, rotation.pitch(), flags, RelativeFlag.X_ROT), -90.0F, 90.0F);
        int proofTransaction = appendTrailingProofTransaction(event, player);

        player.getSetbackTeleportUtil()
                .addImmediatePlayerRotationTeleport(
                        flags, proofTransaction, sourceYaw, sourcePitch, finalYaw, finalPitch);
    }

    private void handleMoveVehicle(
            PacketSendEvent<ClientboundMoveVehicle> event, CultPlayer player, ClientboundMoveVehicle vehicleMove) {
        PacketEntity controlledRoot = clientVisibleLocalAuthoritativeVehicleRoot(player);
        if (controlledRoot == null) return;

        Vec3 finalPos = new Vec3(
                vehicleMove.position().x(),
                vehicleMove.position().y(),
                vehicleMove.position().z());
        float finalYaw = vehicleMove.yaw();
        float finalPitch = vehicleMove.pitch();
        int rootVehicleId = controlledRoot.getEntityId();
        Vec3 currentSerializedPosition = currentSerializedVehiclePosition(controlledRoot);
        boolean snaps = clientboundMoveVehicleSnaps(currentSerializedPosition, finalPos);
        Vec3 expectedResponsePosition =
                snaps || currentSerializedPosition == null ? finalPos : currentSerializedPosition;

        if (event.isCancelled()) {
            return;
        }

        if (player.isBedrockMovement() && BedrockVehicleControl.isSupported(controlledRoot)) {
            // Geyser translates this packet normally. Cult tracks its own rewind setbacks when sent.
            return;
        }

        if (!player.getSetbackTeleportUtil().isSendingSetback) {
            player.sendTransaction();
        }
        trackClientboundMoveVehicle(
                player,
                rootVehicleId,
                finalPos,
                expectedResponsePosition,
                finalYaw,
                finalPitch,
                player.lastTransactionSent.get(),
                snaps);
    }

    private void trackClientboundMoveVehicle(
            CultPlayer player,
            int rootVehicleId,
            Vec3 finalPos,
            Vec3 expectedResponsePosition,
            float finalYaw,
            float finalPitch,
            int proofTransaction,
            boolean snaps) {
        player.latencyUtils.addRealTimeTask(proofTransaction, () -> {
            Integer currentServerVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
            if (currentServerVehicle == null || currentServerVehicle != rootVehicleId) {
                return;
            }

            PacketEntity controlledRoot = player.compensatedEntities.getEntity(rootVehicleId);
            if (controlledRoot != null) {
                if (snaps) {
                    controlledRoot.setPositionRaw(
                            GetBoundingBox.getPacketEntityBoundingBox(
                                    player, finalPos.x, finalPos.y, finalPos.z, controlledRoot),
                            finalYaw,
                            finalPitch);
                    if (controlledRoot instanceof PacketEntityTrackXRot yawTrackedRoot) {
                        // ClientPacketListener#handleMoveVehicle snaps rotation with position.
                        yawTrackedRoot.packetYaw = finalYaw;
                        yawTrackedRoot.interpYaw = finalYaw;
                        yawTrackedRoot.steps = 0;
                    }
                }
            }
        });
        // An unsnapped response serializes the current physical position.
        player.getSetbackTeleportUtil()
                .addVehicleTeleport(
                        rootVehicleId, proofTransaction, expectedResponsePosition, MOVE_VEHICLE_SNAP_EPSILON);
        player.getSetbackTeleportUtil().updateSafeVehiclePosition(expectedResponsePosition);
    }

    private PacketEntity clientVisibleLocalAuthoritativeVehicleRoot(CultPlayer player) {
        PacketEntity root = player.compensatedEntities.getSelf().getRiding();
        if (root == null && player.compensatedEntities.vehicles.serverPlayerVehicle != null) {
            root = player.compensatedEntities.getEntity(player.compensatedEntities.vehicles.serverPlayerVehicle);
        }

        // ClientPacketListener#handleMoveVehicle uses the client's root vehicle.
        return player.compensatedEntities.vehicles.canClientEchoLocalAuthoritativeMoveVehiclePacket(root) ? root : null;
    }

    private static boolean clientboundMoveVehicleSnaps(Vec3 currentSerializedPosition, Vec3 packetPosition) {
        return currentSerializedPosition == null
                || packetPosition.distanceTo(currentSerializedPosition) > MOVE_VEHICLE_SNAP_EPSILON;
    }

    private static Vec3 currentSerializedVehiclePosition(PacketEntity vehicle) {
        if (vehicle.newPacketLocation != null && vehicle.newPacketLocation.hasActiveInterpolationTarget()) {
            SimpleCollisionBox exactCurrent = vehicle.newPacketLocation.getExactMovementLocation();
            return positionFromPacketEntityBox(
                    exactCurrent == null ? vehicle.newPacketLocation.getTargetLocation() : exactCurrent);
        }
        return vehicle.clientPhysicalPosition != null ? vehicle.clientPhysicalPosition : vehicle.desyncClientPos;
    }

    private static Vec3 positionFromPacketEntityBox(SimpleCollisionBox box) {
        return new Vec3((box.maxX - box.minX) / 2.0D + box.minX, box.minY, (box.maxZ - box.minZ) / 2.0D + box.minZ);
    }

    private static float calculateRotation(float current, float change, RelativeFlag flags, RelativeFlag relativeFlag) {
        return (flags.isSet(relativeFlag.getMask()) ? current : 0.0F) + change;
    }

    private static RelativeFlag rotationTeleportFlags(ClientboundPlayerRotation rotation) {
        int mask = RelativeFlag.X.getMask() | RelativeFlag.Y.getMask() | RelativeFlag.Z.getMask();
        // 1.21.2-1.21.3 rotations are always absolute and expose only yRot/xRot.
        if (rotation.relativeYaw()) {
            mask |= RelativeFlag.Y_ROT.getMask();
        }
        if (rotation.relativePitch()) {
            mask |= RelativeFlag.X_ROT.getMask();
        }
        return new RelativeFlag(mask);
    }

    private static int appendTrailingProofTransaction(PacketSendEvent<?> event, CultPlayer player) {
        CultPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForDeferredSend();
        if (transaction == null) {
            return -1;
        }

        event.getWritesAfterSend().add(transaction.packet());
        event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(transaction));
        return transaction.transaction();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
