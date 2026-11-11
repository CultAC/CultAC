package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.runner.KnockbackHandler;
import ac.cult.cultac.events.packets.patch.ResyncWorldUtil;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.event.events.GrimPlayerSetbackEvent;
import ac.grim.grimac.api.event.events.GrimTeleportEvent;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * Writes a correction to the client with raw packets, never the server's teleport API.
 *
 * <ul>
 *   <li>A Java player correction is one player_position whose absolute delta carries the velocity, tracked before it is
 *       written, so the client cannot take the position without the velocity, then two teleport_entity packets for the
 *       player that set its onGround to the correction's ground. A vehicle correction is a move_vehicle
 *       and the vehicle's motion; a Bedrock one is a teleport or a rewind.
 *   <li>A Java correction blocks movement from the moment it is requested; its proofs and packets are written together
 *       at their outbound position.
 *   <li>Nothing is sent to a client that cannot take play packets or has no level.
 *   <li>Paper sees a stand-in move to the target instead of the rejected movement, forwarded at most once per client
 *       tick.
 *   <li>Every correction resyncs the blocks around the player, at most every five seconds.
 * </ul>
 */
public class CorrectionSender {
    // Farther than the 64 blocks (distanceToSqr > 4096) ClientPacketListener#setValuesFromPositionPacket interpolates.
    private static final double GROUND_HOP = 128.0D;

    private final CultPlayer player;
    private final CorrectionState corrections;
    private final JavaTeleportTracker javaTeleports;
    private final BedrockTeleportTracker bedrock;
    private final VehicleTeleportTracker vehicles;

    public CorrectionSender(
            CultPlayer player,
            CorrectionState corrections,
            JavaTeleportTracker javaTeleports,
            BedrockTeleportTracker bedrock,
            VehicleTeleportTracker vehicles) {
        this.player = player;
        this.corrections = corrections;
        this.javaTeleports = javaTeleports;
        this.bedrock = bedrock;
        this.vehicles = vehicles;
    }

    private ServerboundMovePlayer pendingServerMove;
    private boolean forwardedPositionThisTick;

    public boolean hasForwardedPositionThisTick() {
        return forwardedPositionThisTick;
    }

    public void markForwardedPositionThisTick() {
        forwardedPositionThisTick = true;
    }

    public void resetForwardedPositionThisTick() {
        forwardedPositionThisTick = false;
    }

    public void setPendingServerMove(ServerboundMovePlayer move) {
        pendingServerMove = move;
    }

    public ServerboundMovePlayer takePendingServerMove() {
        var move = pendingServerMove;
        pendingServerMove = null;
        return move;
    }

    private static final class Channels {
        private static final GrimTeleportEvent.Channel TELEPORT =
                CultAPI.INSTANCE.getEventBus().get(GrimTeleportEvent.class);
        private static final GrimPlayerSetbackEvent.Channel PLAYER_SETBACK =
                CultAPI.INSTANCE.getEventBus().get(GrimPlayerSetbackEvent.class);
    }

    private long lastWorldResync = 0;

    // Only let us full resync once every five seconds to prevent unneeded bukkit load
    public void resyncWorld() {
        if (System.currentTimeMillis() - lastWorldResync > 5 * 1000) {
            final SimpleCollisionBox expandedBox = player.boundingBox.copy().expand(1);
            ResyncWorldUtil.resyncPositions(player, expandedBox);
            lastWorldResync = System.currentTimeMillis();
        }
    }

    private final Random random = new Random();

    public int nextTeleportId() {
        return random.nextInt() | Integer.MIN_VALUE;
    }

    public void sendSetback(SetBackData data) {
        sendSetback(data, false);
    }

    public void sendSetback(SetBackData data, boolean simulateVehicle) {
        sendSetback(data, simulateVehicle, false);
    }

    public void sendSetback(SetBackData data, boolean simulateVehicle, boolean dismounted) {
        sendSetback(data, simulateVehicle, dismounted, null, null, null);
    }

    /**
     * As the other {@code sendSetback}; once the teleport is written, the player's observer learns its {@code reason}
     * (null tells it nothing), the {@code cause} that reason gave and the {@code check} a violation names, either null.
     */
    public void sendSetback(
            SetBackData data,
            boolean simulateVehicle,
            boolean dismounted,
            SetbackReason reason,
            String cause,
            Check check) {
        // Nothing is tracked for a correction that cannot reach the client; login sends a new position.
        if (!player.canSendPlayPackets()) return;
        if (!player.user.getCultConnection().clientHasLevel()) {
            // A client without a level cannot take a teleport; the play login that gives it one places it anew.
            if (corrections.required() == data) corrections.drop();
            return;
        }
        if (!player.isBedrockMovement() && !player.user.isPreparingWrites()) {
            // Block movement as soon as the correction is requested. Allocate its
            // proofs and dispatch its packets together at their outbound position,
            // while isSendingSetback/isSetbackVal still describe those packets.
            data.setPlugin(false);
            corrections.require(data);
            player.user.executeAfterWrites(() -> sendSetback(data, simulateVehicle, dismounted, reason, cause, check));
            return;
        }
        corrections.setSending(true);
        Vec3 position = data.getTeleportData().getLocation();

        try {
            // Send a transaction now to make sure there's always transactions around teleport
            player.sendTransaction();

            int teleportId = nextTeleportId();
            data.setPlugin(false);
            data.getTeleportData().setTeleportId(teleportId);
            data.getTeleportData().setTransaction(player.lastTransactionSent.get());

            final int vehicleEntityId = dismounted
                    ? Integer.MIN_VALUE
                    : SetbackEntity.current(player).vehicleId();
            boolean bedrockVehicle = player.isBedrockMovement()
                    && BedrockVehicleControl.isSupported(player.compensatedEntities.getEntity(vehicleEntityId));

            // Player is in a vehicle
            if (vehicleEntityId != Integer.MIN_VALUE) {
                // Don't setback the wrong vehicle
                if (player.compensatedEntities.vehicles.serverPlayerVehicle == null
                        || player.compensatedEntities.vehicles.serverPlayerVehicle != vehicleEntityId) {
                    return;
                }

                // PacketServerTeleport observes this packet and queues the exact
                // vanilla snap/echo response position for the vehicle teleport.
                if (bedrockVehicle) {
                    vehicles.bedrockSetbackSent(
                            data,
                            vehicleEntityId,
                            BedrockVehiclePredictionState.setbackPassengerPosition(
                                    player,
                                    player.compensatedEntities.getEntity(vehicleEntityId),
                                    data.getProfileState(),
                                    position,
                                    bedrock.getActiveBedrockCoordinateFrame()));
                    int transaction = data.getTeleportData().getTransaction();
                    vehicles.addBedrockVehicleTeleport(vehicleEntityId, transaction, position);
                    if (simulateVehicle) {
                        player.runSafely(() ->
                                player.bedrockState.movementCorrections.requestSetback(vehicleEntityId, transaction));
                    } else {
                        sendBedrockMovementCorrection(
                                vehicleEntityId,
                                position,
                                data.getVelocity() == null ? Vec3.ZERO : data.getVelocity(),
                                player.xRot,
                                player.yRot,
                                data.isExpectedOnGround(),
                                transaction,
                                data.getProfileState());
                    }
                } else {
                    boolean vehicleRotation = !Float.isNaN(data.getVehicleYaw());
                    player.user.write(new CultWrite(
                            new ClientboundMoveVehicle(
                                    new Vec3d(position.x, position.y, position.z),
                                    vehicleRotation ? data.getVehicleYaw() : player.xRot,
                                    vehicleRotation ? data.getVehiclePitch() : player.yRot),
                            false));
                }
            } else {
                vehicles.clearBedrockVehicleSetback();
                // ClientPacketListener#setValuesFromPositionPacket sets the delta movement to a teleport's absolute
                // one (PositionMoveRotation#calculateAbsolute) in the handler that answers it. A Java correction
                // carries its velocity there, so the client cannot accept the position without the velocity.
                Vec3 delta = data.getVelocity() == null ? Vec3.ZERO : data.getVelocity();
                // The client takes the ground only where this correction's tick or safe position proves it.
                boolean onGround = data.getGroundAfter() == DesyncStatus.TRUE;
                // Track the correction before sending; its outbound packet is silenced below.
                if (!player.isBedrockMovement()) {
                    data.setGroundAfter(DesyncStatus.fromBoolean(onGround));
                    javaTeleports.addSentCorrection(
                            position,
                            delta,
                            data.getTeleportData().getTransaction(),
                            new RelativeFlag(0b11000),
                            teleportId,
                            onGround);
                    corrections.correctionSent(data.getTeleportData().getTransaction());
                }
                // Receive the player's position packet to make setbacks appear smooth for other players (and to stop
                // vanilla ac setbacks)
                // For Bedrock this move is Paper's teleport response, which is never on ground.
                boolean serverMoveOnGround = !player.isBedrockMovement() && data.isExpectedOnGround();
                player.user.execute(() -> setPendingServerMove(new ServerboundMovePlayer(
                        position.x, position.y, position.z, 0, 0, serverMoveOnGround, false, true, false)));
                if (player.isBedrockMovement()) {
                    corrections.require(data);
                    GeyserBedrockBridgeRuntime.sendPlayerTeleport(
                            player.user,
                            position,
                            player.xRot,
                            player.yRot,
                            data.isExpectedOnGround(),
                            data.getTeleportData().getTransaction());
                } else {
                    sendPlayerSetbackPackets(
                            teleportId,
                            position,
                            delta,
                            data.getTeleportData().getFlags().getMask(),
                            onGround);
                }
            }

            // required setback applies for both our own vehicle teleports and regular teleports
            corrections.require(data);
            PlayerObserver observer = player.observer;
            if (observer != null && reason != null) observer.setbackSent(reason, cause, check);

            // Preserve the public packet-level and semantic setback signals from
            // the PacketEvents path after the teleport is tracked and emitted.
            long now = System.currentTimeMillis();
            Channels.TELEPORT.fire(ac.cult.cultac.runtime.RuntimeEnvironment.outwardUser(player), teleportId, now);
            Channels.PLAYER_SETBACK.fire(
                    ac.cult.cultac.runtime.RuntimeEnvironment.outwardUser(player),
                    teleportId,
                    position.x,
                    position.y,
                    position.z,
                    now);

            final KnockbackHandler knockbackHandler = player.checkManager.getKnockbackHandler();
            // A Java correction of the player carries its velocity in its player_position.
            boolean velocityInTeleport = !player.isBedrockMovement() && vehicleEntityId == Integer.MIN_VALUE;
            if (!velocityInTeleport
                    && !bedrockVehicle
                    && data.getVelocity() != null
                    && (dismounted || data.getVelocity().lengthSqr() > 0 || vehicleEntityId != Integer.MIN_VALUE)) {
                knockbackHandler.setSetbackVal(true);
                player.user.write(new CultWrite(
                        new ClientboundEntityMotion(
                                vehicleEntityId == Integer.MIN_VALUE ? player.entityID : vehicleEntityId,
                                new Vec3d(data.getVelocity().x, data.getVelocity().y, data.getVelocity().z)),
                        false));
                knockbackHandler.setSetbackVal(false);
            } else {
                player.sendTransaction();
            }
        } finally {
            corrections.setSending(false);
        }
    }

    /**
     * ClientPacketListener#handleMovePlayer leaves onGround to the movement the correction rejects, so the client's
     * next tick could jump or step up from the air. #handleTeleportEntity on the player's own id calls
     * Entity#setOnGround, but #setValuesFromPositionPacket interpolates a move of 64 blocks or less, and nothing on the
     * client cancels that interpolation. The player hops away and back, each hop farther than that, so both only set
     * the position; the second sets the ground at the target. Rotation and delta stay as the correction left them.
     * The three go out in one client bundle, so no client tick or frame runs between them.
     */
    private void sendPlayerSetbackPackets(
            int teleportId, Vec3 position, Vec3 delta, int relativeMask, boolean onGround) {
        var point = new Vec3d(position.x, position.y, position.z);
        var relatives = Relative.unpack(relativeMask);
        var unchanged =
                EnumSet.of(Relative.Y_ROT, Relative.X_ROT, Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);
        var away = new Vec3d(position.x, position.y + GROUND_HOP, position.z);
        // One client bundle: ClientPacketListener#handleBundlePacket handles all three before the next tick or frame.
        player.user
                .getCultConnection()
                .write(
                        List.of(
                                new CultWrite(
                                        new ClientboundPlayerPosition(
                                                teleportId,
                                                point,
                                                new Vec3d(delta.x, delta.y, delta.z),
                                                0.0F,
                                                0.0F,
                                                relatives),
                                        true),
                                new CultWrite(
                                        new ClientboundTeleportEntity(
                                                player.entityID, away, Vec3d.ZERO, 0.0F, 0.0F, unchanged, false),
                                        true),
                                new CultWrite(
                                        new ClientboundTeleportEntity(
                                                player.entityID, point, Vec3d.ZERO, 0.0F, 0.0F, unchanged, onGround),
                                        true)),
                        true);
    }

    public void sendBedrockMovementCorrection(
            int vehicleId, Vec3 position, Vec3 velocity, float yaw, float pitch, boolean onGround, int transaction) {
        sendBedrockMovementCorrection(vehicleId, position, velocity, yaw, pitch, onGround, transaction, null);
    }

    private void sendBedrockMovementCorrection(
            int vehicleId,
            Vec3 position,
            Vec3 velocity,
            float yaw,
            float pitch,
            boolean onGround,
            int transaction,
            PredictionSetbackState setback) {
        player.runSafely(() -> player.bedrockState.movementCorrections.request(
                player, vehicleId, position, velocity, yaw, pitch, onGround, transaction, setback));
    }
    /** Observe-only lets go of the held stand-in move. */
    public void dropPendingServerMove() {
        pendingServerMove = null;
    }
}
