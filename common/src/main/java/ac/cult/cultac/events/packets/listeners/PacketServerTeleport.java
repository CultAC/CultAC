package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.world.phys.Vec3;

public class PacketServerTeleport {
    private static final double MOVE_VEHICLE_SNAP_EPSILON = 1.0E-5D;

    //LOW

    @CultPacketHandler
    public void onAcceptTeleportation(PacketReceiveEvent event, CultPlayer player, ServerboundAcceptTeleportationPacket packet) {
        if (player.isBedrockMovement()) {
            // Geyser's Java acknowledgement does not prove Bedrock processed the teleport.
            return;
        }
        // Before 26.3 the position response is a separate PosRot packet.
        // Decode the native format: Via may translate an older client's response.
        NmsPacketUtil.TeleportAcknowledgement ack = NmsPacketUtil.readTeleportAcknowledgement(packet);
        if (ack.position() == null) return;

        TeleportAcceptData accepted = player.getSetbackTeleportUtil()
                .checkTeleportQueue(ack.position().x, ack.position().y, ack.position().z);
        if (accepted.isTeleport()) {
            CheckManagerListener.applyTeleportResponse(player, accepted, ack.yaw(), ack.pitch());
        }
    }

    @CultPacketHandler
    public void onPlayerPosition(PacketSendEvent event, CultPlayer player, ClientboundPlayerPositionPacket packet) {
        handlePlayerPosition(event, player, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket")
    public void onPlayerRotation(PacketSendEvent event, CultPlayer player, Packet<?> packet) {
        handlePlayerRotation(event, player, packet);
    }

    @CultPacketHandler
    public void onMoveVehicle(PacketSendEvent event, CultPlayer player, ClientboundMoveVehiclePacket packet) {
        handleMoveVehicle(event, player, packet);
    }

    private void handlePlayerPosition(PacketSendEvent event, CultPlayer player, ClientboundPlayerPositionPacket teleport) {
        PlayerPositionData change = readPlayerPosition(teleport);
        Vec3 nativePos = change.position();
        Vec3 pos = new Vec3(nativePos.x, nativePos.y, nativePos.z);
        Vec3 deltaMovement = change.deltaMovement();
        RelativeFlag flags = change.flags();
        float sourceYaw = player.xRot;
        float sourcePitch = player.yRot;
        float finalYaw = calculateRotation(sourceYaw, change.yRot(), flags, RelativeFlag.Y_ROT);
        float finalPitch = clamp(calculateRotation(sourcePitch, change.xRot(), flags, RelativeFlag.X_ROT), -90.0F, 90.0F);
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
            event.getPacketsBeforeSend().add(proof.packet());
            event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(proof));
        }
        int lastTransactionSent = proof == null ? player.lastTransactionSent.get() : proof.transaction();
        event.getTasksAfterSend().add(player::sendTransaction);

        player.getSetbackTeleportUtil().addSentTeleport(pos, deltaMovement, lastTransactionSent, flags, true, change.teleportId(), sourceYaw, sourcePitch, finalYaw, finalPitch);
    }

    private static PlayerPositionData readPlayerPosition(Packet<?> packet) {
        try {
            packet.getClass().getMethod("change");
            Object value = NmsPacketUtil.invokeNoArg(packet, "change");
            return new PlayerPositionData(
                    NmsPacketUtil.intValue(packet, "id"),
                    (Vec3) NmsPacketUtil.invokeNoArg(value, "position"),
                    (Vec3) NmsPacketUtil.invokeNoArg(value, "deltaMovement"),
                    NmsPacketUtil.floatValue(value, "yRot"),
                    NmsPacketUtil.floatValue(value, "xRot"),
                    new RelativeFlag(relativeMask((Iterable<?>) NmsPacketUtil.invokeNoArg(packet, "relatives")))
            );
        } catch (NoSuchMethodException ignored) {
            Vec3 position = new Vec3(
                    ((Number) NmsPacketUtil.invokeNoArg(packet, "getX")).doubleValue(),
                    ((Number) NmsPacketUtil.invokeNoArg(packet, "getY")).doubleValue(),
                    ((Number) NmsPacketUtil.invokeNoArg(packet, "getZ")).doubleValue()
            );
            return new PlayerPositionData(
                    NmsPacketUtil.intValue(packet, "getId"),
                    position,
                    Vec3.ZERO,
                    NmsPacketUtil.floatValue(packet, "getYRot"),
                    NmsPacketUtil.floatValue(packet, "getXRot"),
                    new RelativeFlag(relativeMask((Iterable<?>) NmsPacketUtil.invokeNoArg(packet, "getRelativeArguments")))
            );
        }
    }

    private static int relativeMask(Iterable<?> relatives) {
        int mask = 0;
        for (Object relative : relatives) {
            if (!(relative instanceof Enum<?> value)) {
                continue;
            }
            mask |= switch (value.name()) {
                case "X" -> RelativeFlag.X.getMask();
                case "Y" -> RelativeFlag.Y.getMask();
                case "Z" -> RelativeFlag.Z.getMask();
                case "Y_ROT" -> RelativeFlag.Y_ROT.getMask();
                case "X_ROT" -> RelativeFlag.X_ROT.getMask();
                case "DELTA_X" -> RelativeFlag.DELTA_X.getMask();
                case "DELTA_Y" -> RelativeFlag.DELTA_Y.getMask();
                case "DELTA_Z" -> RelativeFlag.DELTA_Z.getMask();
                case "ROTATE_DELTA" -> RelativeFlag.ROTATE_DELTA.getMask();
                default -> 0;
            };
        }
        return mask;
    }

    private void handlePlayerRotation(PacketSendEvent event, CultPlayer player, Packet<?> rotation) {
        RelativeFlag flags = rotationTeleportFlags(rotation);
        float sourceYaw = player.xRot;
        float sourcePitch = player.yRot;
        float finalYaw = calculateRotation(sourceYaw, NmsPacketUtil.floatValue(rotation, "yRot"), flags, RelativeFlag.Y_ROT);
        float finalPitch = clamp(calculateRotation(sourcePitch, NmsPacketUtil.floatValue(rotation, "xRot"), flags, RelativeFlag.X_ROT), -90.0F, 90.0F);
        int proofTransaction = appendTrailingProofTransaction(event, player);

        player.getSetbackTeleportUtil().addImmediatePlayerRotationTeleport(
                flags,
                proofTransaction,
                sourceYaw,
                sourcePitch,
                finalYaw,
                finalPitch
        );
    }

    private void handleMoveVehicle(PacketSendEvent event, CultPlayer player, ClientboundMoveVehiclePacket vehicleMove) {
        PacketEntity controlledRoot = clientVisibleLocalAuthoritativeVehicleRoot(player);
        if (controlledRoot == null) return;

        NmsPacketUtil.MoveVehicleData vehicleData = NmsPacketUtil.readMoveVehicle(vehicleMove);
        Vec3 vehiclePos = vehicleData.position();
        Vec3 finalPos = new Vec3(vehiclePos.x, vehiclePos.y, vehiclePos.z);
        float finalYaw = vehicleData.yaw();
        float finalPitch = vehicleData.pitch();
        int rootVehicleId = controlledRoot.getEntityId();
        Vec3 currentSerializedPosition = currentSerializedVehiclePosition(controlledRoot);
        boolean snaps = clientboundMoveVehicleSnaps(currentSerializedPosition, finalPos);
        Vec3 expectedResponsePosition = snaps || currentSerializedPosition == null ? finalPos : currentSerializedPosition;

        if (event.isCancelled()) {
            return;
        }

        if (!player.getSetbackTeleportUtil().isSendingSetback) {
            player.sendTransaction();
        }
        trackClientboundMoveVehicle(player, rootVehicleId, finalPos, expectedResponsePosition, finalYaw, finalPitch,
                player.lastTransactionSent.get(), snaps);
    }

    private void trackClientboundMoveVehicle(CultPlayer player,
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
                            GetBoundingBox.getPacketEntityBoundingBox(player, finalPos.x, finalPos.y, finalPos.z, controlledRoot),
                            finalYaw,
                            finalPitch
                    );
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
        player.getSetbackTeleportUtil().addVehicleTeleport(rootVehicleId, proofTransaction,
                expectedResponsePosition, MOVE_VEHICLE_SNAP_EPSILON);
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
            return positionFromPacketEntityBox(exactCurrent == null
                    ? vehicle.newPacketLocation.getTargetLocation()
                    : exactCurrent);
        }
        return vehicle.clientPhysicalPosition != null ? vehicle.clientPhysicalPosition : vehicle.desyncClientPos;
    }

    private static Vec3 positionFromPacketEntityBox(SimpleCollisionBox box) {
        return new Vec3(
                (box.maxX - box.minX) / 2.0D + box.minX,
                box.minY,
                (box.maxZ - box.minZ) / 2.0D + box.minZ
        );
    }

    private static float calculateRotation(float current, float change, RelativeFlag flags, RelativeFlag relativeFlag) {
        return (flags.isSet(relativeFlag.getMask()) ? current : 0.0F) + change;
    }

    private static RelativeFlag rotationTeleportFlags(Packet<?> rotation) {
        int mask = RelativeFlag.X.getMask() | RelativeFlag.Y.getMask() | RelativeFlag.Z.getMask();
        // 1.21.2-1.21.3 rotations are always absolute and expose only yRot/xRot.
        if (NmsPacketUtil.booleanValueOrDefault(rotation, false, "relativeY")) {
            mask |= RelativeFlag.Y_ROT.getMask();
        }
        if (NmsPacketUtil.booleanValueOrDefault(rotation, false, "relativeX")) {
            mask |= RelativeFlag.X_ROT.getMask();
        }
        return new RelativeFlag(mask);
    }

    private static int appendTrailingProofTransaction(PacketSendEvent event, CultPlayer player) {
        CultPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForDeferredSend();
        if (transaction == null) {
            return -1;
        }

        event.getPacketsAfterSend().add(transaction.packet());
        event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(transaction));
        return transaction.transaction();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record PlayerPositionData(
            int teleportId,
            Vec3 position,
            Vec3 deltaMovement,
            float yRot,
            float xRot,
            RelativeFlag flags
    ) {
    }
}
