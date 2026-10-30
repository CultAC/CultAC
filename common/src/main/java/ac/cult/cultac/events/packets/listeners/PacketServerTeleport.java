package ac.cult.cultac.events.packets.listeners;

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
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.VehicleTeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;

public class PacketServerTeleport {
    private static final double MOVE_VEHICLE_SNAP_EPSILON = 1.0E-5F;

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
        event.setAuthoredTeleportResponse(player.getSetbackTeleportUtil().takeAuthoredTeleportAcknowledgment(ack.id()));
        player.getSetbackTeleportUtil().teleportAnswered(ack.id(), ack.position() != null);
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
        float absolutePitch = calculateRotation(sourcePitch, teleport.pitch(), flags, RelativeFlag.X_ROT);
        float finalPitch = TeleportData.clientPitch(player.getClientVersion(), absolutePitch);
        boolean initialSpawnTeleport = player.getSetbackTeleportUtil().getRequiredSetBack() == null;

        // This is the first packet sent to the client which we need to track
        if (initialSpawnTeleport) {
            // Player teleport event gets called AFTER player join event
            player.x = pos.x;
            player.y = pos.y;
            player.z = pos.z;
            player.compensatedEntities.observeLocalPlayerPosition(pos);
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
        CultPlayer.TrackedTransaction proof =
                player.createTrackedTransactionPacketForDeferredSend(CultPlayer.PING_LEADING);
        if (proof != null) {
            event.getWritesBeforeSend().add(proof.packet());
            event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(proof));
        }
        int lastTransactionSent = proof == null ? player.lastTransactionSent.get() : proof.transaction();
        event.getTasksAfterSend().add(player::sendTrailingTransaction);

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
                        absolutePitch,
                        event.isAuthored());
    }

    private void handlePlayerRotation(
            PacketSendEvent<ClientboundPlayerRotation> event, CultPlayer player, ClientboundPlayerRotation rotation) {
        RelativeFlag flags = rotationTeleportFlags(rotation);
        float sourceYaw = player.xRot;
        float sourcePitch = player.yRot;
        float finalYaw = calculateRotation(sourceYaw, rotation.yaw(), flags, RelativeFlag.Y_ROT);
        float finalPitch =
                clamp(calculateRotation(sourcePitch, rotation.pitch(), flags, RelativeFlag.X_ROT), -90.0F, 90.0F);
        // A ping earlier in the bundle is answered in the task that handles the rotation, so the next Rot after its
        // pong is the echo.
        int bundleTransaction = player.appendLeadingFlushProofTransaction(event);
        // The pong must follow the Rot that ClientPacketListener#handleRotatePlayer sends, so the proof never
        // shares an earlier ping in the bundle.
        CultPlayer.TrackedTransaction proof = player.appendResponseProofTransaction(event);
        int proofTransaction = proof == null ? -1 : proof.transaction();

        player.getSetbackTeleportUtil()
                .addImmediatePlayerRotationTeleport(
                        flags,
                        bundleTransaction,
                        proofTransaction,
                        sourceYaw,
                        sourcePitch,
                        finalYaw,
                        finalPitch,
                        rotation);
    }

    private void handleMoveVehicle(
            PacketSendEvent<ClientboundMoveVehicle> event, CultPlayer player, ClientboundMoveVehicle vehicleMove) {
        PacketEntity controlledRoot = clientVisibleVehicleCorrectionRoot(player);
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

        // ClientPacketListener#handleMoveVehicle sends the vehicle's MoveVehicle from the handler. Only a ping after
        // the packet orders its pong after that echo, so a later packet in the flush, such as a dismount, never
        // shares an earlier ping. A setback's echo keeps the transaction of the setback it completes.
        int lastSent = player.lastTransactionSent.get();
        CultPlayer.TrackedTransaction response = player.appendResponseProofTransaction(event);
        int proofTransaction = player.getSetbackTeleportUtil().isSendingSetback || response == null
                ? lastSent
                : response.transaction();
        trackClientboundMoveVehicle(
                player,
                rootVehicleId,
                finalPos,
                expectedResponsePosition,
                finalYaw,
                finalPitch,
                proofTransaction,
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
        // Authority can change before the next carried-slot claim. Only the handler's non-tick echo proves that
        // this correction was applied; a passive root ignores it even when the following ping is answered.
        player.getSetbackTeleportUtil()
                .addVehicleTeleport(
                        rootVehicleId,
                        proofTransaction,
                        expectedResponsePosition,
                        VehicleTeleportData.correction(rootVehicleId, finalPos, finalYaw, finalPitch, snaps));
    }

    private PacketEntity clientVisibleVehicleCorrectionRoot(CultPlayer player) {
        // ClientPacketListener#handleMoveVehicle moves the client's Entity#getRootVehicle as it handles the packet,
        // after every set_passengers sent before it: a boat the server moved the player to is already that root.
        var vehicles = player.compensatedEntities.vehicles;
        PacketEntity root = vehicles.rootVehicleAsSent();
        if (root == null) return null;

        // The packet handler can already see a different held item before MultiPlayerGameMode claims its slot.
        // This only queues a possible response; tick travel and passenger geometry still require proven control.
        return vehicles.mayLocalPlayerControl(root) || vehicles.canClientEchoLocalAuthoritativeMoveVehiclePacket(root)
                ? root
                : null;
    }

    private static boolean clientboundMoveVehicleSnaps(Vec3 currentSerializedPosition, Vec3 packetPosition) {
        return currentSerializedPosition == null
                || packetPosition.distanceTo(currentSerializedPosition) > MOVE_VEHICLE_SNAP_EPSILON;
    }

    private static Vec3 currentSerializedVehiclePosition(PacketEntity vehicle) {
        if (vehicle.newPacketLocation != null && vehicle.newPacketLocation.hasActiveInterpolationTarget()) {
            return vehicle.newPacketLocation.targetPosition();
        }
        return vehicle.clientPhysicalPosition != null ? vehicle.clientPhysicalPosition : vehicle.desyncClientPos;
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
