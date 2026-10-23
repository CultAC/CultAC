package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseProperties;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.anticheat.StringReturner;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.debug.Debuggable;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class CompensatedVehicleState implements Debuggable {
    private static final String DEBUG_NAME = "Buffer";
    private static final int VEHICLE_SWITCH_BUFFER_CLIENT_TICKS = 5;
    private static final double VEHICLE_SWITCH_BUFFER_MAX = 0.20D;
    private static final double VEHICLE_SWITCH_BUFFER_REFILL_PER_PREDICTION = 0.01D;
    private static final double VEHICLE_SWITCH_BUFFER_MAX_PER_CLIENT_TICK = 0.05D;
    private static final double VEHICLE_SWITCH_BUFFER_EPSILON = 1.0E-9D;
    private static final double SADDLE_HAND_OVER_REPORT_EPSILON = 1.0E-6D;

    private final CultPlayer player;
    private final CompensatedEntities entities;
    private double vehicleSwitchBuffer;
    private double vehicleSwitchBufferSpentThisClientTick;
    private int lastVehicleSwitchClientTick = Integer.MIN_VALUE;
    private int lastVehicleSwitchBufferSpendClientTick = Integer.MIN_VALUE;

    @Nullable
    private Integer lastVehicleSwitchBufferVehicleId;

    @Nullable
    private PredictionResult lastVehicleSwitchBufferRefillPrediction;

    @Nullable
    private Integer saddleHandOverVehicleId;

    private int saddleHandOverLastClientTick;
    private int saddleHandOverTravelClientTick = Integer.MIN_VALUE;

    @Nullable
    private Vec3 saddleHandOverTravel;

    @Nullable
    private Vec3 saddleHandOverPhysicalEnd;

    @Nullable
    public Integer serverPlayerVehicle;

    @Nullable
    public int[] serverPlayerVehiclePassengers;

    @Nullable
    public Integer serverPlayerVehicleTransaction;

    CompensatedVehicleState(CultPlayer player, CompensatedEntities entities) {
        this.player = player;
        this.entities = entities;
    }

    @Override
    public String debugName() {
        return DEBUG_NAME;
    }

    @Override
    public void debug(StringReturner details) {
        ac.cult.cultac.checks.impl.prediction.DebugHandler debugHandler =
                player.checkManager == null ? null : player.checkManager.getDebugHandler();
        if (debugHandler != null) {
            debugHandler.relayDebug(debugName(), details);
        }
    }

    public void onClientTickEnd() {
        PacketEntity root = getVelocityMovementVehicle();
        if (!player.isBedrockMovement()
                && root != null
                && !canClientAuthoritativelyMoveVisibleRoot(root)
                && !canServerPlayerVehicleBeLocalAuthoritative()
                && !hasActiveInterpolationTarget(root)) {
            snapToTrackedServerPosition(root);
        }
        if (!player.isBedrockMovement()
                && root != null
                && root.isLivingEntity()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                && !canClientAuthoritativelyMoveVisibleRoot(root)) {
            decayUncontrolledRootVelocity(root);
        }

        entities.updatePassengerPositions();
        Vec3 provenPassengerDelta = player.packetStateData.lastPacketProvenVehiclePhysicalMovement;
        if (provenPassengerDelta != null && entities.playerEntity.getRiding() != null) {
            entities.playerEntity.deltaMovement = provenPassengerDelta;
        }
    }

    /**
     * LivingEntity#aiStep of a living root the client does not move: it scales the delta movement by 0.98 while the
     * root is not interpolating, then zeroes each component below 0.003.
     */
    private void decayUncontrolledRootVelocity(PacketEntity root) {
        boolean interpolating = hasActiveInterpolationTarget(root);
        root.deltaMovement = uncontrolledLivingAiStep(root.deltaMovement, interpolating);
        List<Vec3> velocities = new ArrayList<>();
        for (Vec3 velocity : player.checkManager.getSimulationProcessor().getValidPlayerStartingVels()) {
            Vec3 decayed = uncontrolledLivingAiStep(velocity, interpolating);
            if (!velocities.contains(decayed)) velocities.add(decayed);
        }
        player.checkManager.getSimulationProcessor().seedStartingVelocities(velocities);
    }

    private static Vec3 uncontrolledLivingAiStep(Vec3 velocity, boolean interpolating) {
        Vec3 scaled = interpolating ? velocity : velocity.multiply(0.98D, 0.98D, 0.98D);
        return new Vec3(
                Math.abs(scaled.x) < 0.003D ? 0.0D : scaled.x,
                Math.abs(scaled.y) < 0.003D ? 0.0D : scaled.y,
                Math.abs(scaled.z) < 0.003D ? 0.0D : scaled.z);
    }

    public void updateServerControlledSafeSetbackPositionOnTickEnd() {
        if (player.packetStateData.vehicleMovePacketsThisClientTick != 0) {
            return;
        }

        PacketEntity root = getVelocityMovementVehicle();
        if (root == null
                || serverPlayerVehicle == null
                || serverPlayerVehicle != root.getEntityId()
                || passengerIndex(root) < 0
                || canServerPlayerVehicleBeLocalAuthoritative()) {
            return;
        }

        TrackerData tracked = entities.getTrackedEntity(root.getEntityId());
        if (tracked == null || player.lastTransactionReceived.get() < tracked.getLastTransactionHung()) {
            return;
        }

        Vec3 safePosition = currentExactServerControlledPosition(root);
        if (safePosition != null) {
            player.getSetbackTeleportUtil().updateSafeVehiclePosition(safePosition);
        }
    }

    public boolean applyClientboundVehicleVelocity(@Nullable PacketEntity vehicle, Vec3 velocity) {
        return applyClientboundVehicleVelocity(vehicle, velocity, List.of(velocity));
    }

    private boolean applyClientboundVehicleVelocity(
            @Nullable PacketEntity vehicle, Vec3 velocity, Collection<Vec3> startingVelocities) {
        if (vehicle == null || vehicle != getVelocityMovementVehicle()) return false;
        vehicle.deltaMovement = velocity;
        if (player.isBedrockMovement()
                && ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.isSupported(vehicle)) {
            BedrockVehiclePredictionState.applyVelocity(vehicle, velocity);
        } else {
            player.checkManager.getSimulationProcessor().seedStartingVelocities(startingVelocities);
        }
        return true;
    }

    /** Apply at the acknowledged event boundary, after earlier passenger updates. */
    public void applyBoatBubbleColumnEvent(int entityId, byte event) {
        // Geyser does not translate these Java boat events.
        if (player.isBedrockMovement()) return;
        PacketEntity boat = entities.getEntity(entityId);
        if ((event != 71 && event != 72)
                || boat == null
                || !EntityTypeUtil.isBoat(boat.type)
                || boat.passengers.isEmpty()
                || boat.passengers.getFirst() != entities.getSelf()) return;

        // RC1 AbstractBoat#handleBubbleColumnEffect requires a direct local player
        // controller. Event 71 adds -0.7 Y; event 72 replaces Y, preserving X/Z.
        Vec3 velocity = bubbleColumnVelocity(boat.deltaMovement, event);
        var startingVelocities = player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                .map(candidate -> bubbleColumnVelocity(candidate, event))
                .toList();
        if (!applyClientboundVehicleVelocity(boat, velocity, startingVelocities)) {
            boat.deltaMovement = velocity;
        }
    }

    private static Vec3 bubbleColumnVelocity(Vec3 velocity, byte event) {
        return new Vec3(velocity.x, event == 71 ? velocity.y - 0.7D : 0.6D, velocity.z);
    }

    public void applyAcceptedVehicleTeleportEntityState(
            int entityId,
            Vec3 position,
            float yaw,
            float pitch,
            @Nullable Boolean onGround,
            Vec3 deltaMovement,
            boolean interpolates) {
        PacketEntity entity = entities.getEntity(entityId);
        if (entity == null) return;

        if (player.isBedrockMovement() && entity == entities.playerEntity.getRiding()) {
            player.checkManager
                    .getListener(ac.cult.cultac.checks.impl.bedrock.BedrockMovement.class)
                    .onVehicleTeleport();
        }

        if (player.isBedrockMovement() && BedrockHorseProperties.supports(entity.type) && onGround == null) {
            onGround = false;
        }
        if (interpolates) {
            if (entity instanceof PacketEntityTrackXRot xRotEntity) {
                xRotEntity.packetYaw = yaw;
                xRotEntity.steps = 3;
            }
            entity.onBundleTransaction(false, true, position.x, position.y, position.z, yaw, pitch, player);
            entity.deltaMovement = deltaMovement;
            if (onGround != null) {
                entity.onGround = onGround;
            }
        } else {
            applyAcceptedVehiclePositionState(entityId, position, yaw, pitch, deltaMovement);
            if (onGround != null) {
                entity.onGround = onGround;
            }
            // ClientPacketListener#setValuesFromPositionPacket sets the yaw AbstractBoat#controlBoat turns next tick.
            if (entity.isBoat() && entity == entities.getSelf().getRiding()) player.boatYawBeforeTurn = yaw;
        }

        if (player.isBedrockMovement()
                && ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.isSupported(entity)) {
            BedrockVehiclePredictionState.rebase(
                    entity, player.getSetbackTeleportUtil().getActiveBedrockCoordinateFrame());
        } else if (entity == getVelocityMovementVehicle()) {
            player.checkManager.getSimulationProcessor().seedVehicleStartingVelocity(deltaMovement);
        }
    }

    public void applyAcceptedVehiclePositionState(
            int entityId, Vec3 position, float yaw, float pitch, Vec3 deltaMovement) {
        PacketEntity entity = entities.getEntity(entityId);
        if (entity == null) return;
        if (player.isBedrockMovement() && BedrockHorseProperties.supports(entity.type)) {
            var frame = player.getSetbackTeleportUtil().getActiveBedrockCoordinateFrame();
            var local = frame.toLocal(position);
            position = frame.toWorld(new Vec3((float) local.x, (float) local.y, (float) local.z));
        }
        entity.setPositionRaw(
                GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, entity),
                yaw,
                pitch);
        if (entity == getVelocityMovementVehicle()) {
            entity.deltaMovement = deltaMovement;
        }
    }

    public void applyAcceptedVehiclePositionState(
            int entityId, Vec3 position, float yaw, float pitch, Vec3 deltaMovement, boolean onGround) {
        applyAcceptedVehiclePositionState(entityId, position, yaw, pitch, deltaMovement);
        PacketEntity entity = entities.getEntity(entityId);
        if (entity != null) entity.onGround = onGround;
    }

    public void applyVehiclePacketPosition(
            PacketEntity vehicle, Vec3 to, boolean onGround, float physicalYaw, float physicalPitch) {
        if (vehicle == null) {
            return;
        }

        vehicle.setPositionRaw(
                GetBoundingBox.getPacketEntityBoundingBox(player, to.x, to.y, to.z, vehicle),
                physicalYaw,
                physicalPitch);
        vehicle.onGround = onGround;
    }

    /** {@link #applyVehiclePacketPosition} for a LocalPlayer#tick move of a root the client controls. */
    public void applyControlledVehicleMove(
            PacketEntity vehicle, Vec3 to, boolean onGround, float physicalYaw, float physicalPitch) {
        if (vehicle == null) {
            return;
        }

        Vec3 physical = isSaddleHandOverTravelFor(vehicle) ? saddleHandOverPhysicalEnd : to;
        vehicle.moveLocallyControlledRoot(
                GetBoundingBox.getPacketEntityBoundingBox(player, physical.x, physical.y, physical.z, vehicle),
                physicalYaw,
                physicalPitch);
        vehicle.onGround = onGround;
    }

    public void setServerVehicle(int vehicleId, int[] passengers, int transaction) {
        PacketEntity vehicle = entities.getEntity(vehicleId);
        if (serverPlayerVehicle == null || serverPlayerVehicle != vehicleId) {
            player.getSetbackTeleportUtil().clearVehicleTeleports();
        }
        // Rebuilding passengers does not interrupt the same first passenger's control of this root.
        // Keep its attachment proof; the full passenger graph still updates at this packet's receipt boundary.
        boolean unchangedAttachment = serverPlayerVehicle != null
                && serverPlayerVehicle == vehicleId
                && (Arrays.equals(serverPlayerVehiclePassengers, passengers)
                        || (serverPlayerVehiclePassengers != null
                                && serverPlayerVehiclePassengers.length > 0
                                && passengers.length > 0
                                && serverPlayerVehiclePassengers[0] == player.entityID
                                && passengers[0] == player.entityID));
        serverPlayerVehicle = vehicleId;
        serverPlayerVehiclePassengers = passengers.clone();
        if (!unchangedAttachment) {
            serverPlayerVehicleTransaction = transaction;
        }
        if (canLocalClientAuthoritativelyMoveMountedVehicle(vehicle, passengers)) {
            markVehicleSwitchBufferWindow(vehicle);
        }
    }

    public void setServerVehicleDismount(int vehicleId, int transaction) {
        PacketEntity vehicle = entities.getEntity(vehicleId);
        if (canClientAuthoritativelyMoveVisibleRoot(vehicle)) {
            markVehicleSwitchBufferWindow(vehicle);
        }
        serverPlayerVehicle = vehicleId;
        serverPlayerVehiclePassengers = null;
        serverPlayerVehicleTransaction = transaction;
    }

    public void clearServerVehicle(int vehicleId, int transaction) {
        if (serverPlayerVehicle != null
                && serverPlayerVehicle == vehicleId
                && serverPlayerVehicleTransaction != null
                && serverPlayerVehicleTransaction == transaction) {
            clearServerVehicle();
        }
    }

    public void clearServerVehicle() {
        serverPlayerVehicle = null;
        serverPlayerVehiclePassengers = null;
        serverPlayerVehicleTransaction = null;
        player.getSetbackTeleportUtil().clearVehicleTeleports();
    }

    public PacketEntity getVelocityMovementVehicle() {
        PacketEntity serverVehicle = serverPlayerVehicle == null ? null : entities.getEntity(serverPlayerVehicle);
        return serverVehicle == null ? entities.playerEntity.getRiding() : serverVehicle;
    }

    public void markItemControlledVehicleControlSwitch() {
        PacketEntity vehicle = possibleItemControlledVehicle();
        if (vehicle != null && hasSaddle(vehicle)) {
            markVehicleSwitchBufferWindow(vehicle);
        }
    }

    /**
     * Call after the saddle or harness of {@code vehicle} changes, with whether the local player controlled it before.
     * The vehicle's getControllingPassenger reads that equipment, so the change moves control at the client's next
     * tick, as a mount does.
     */
    public void markSaddleControlSwitch(PacketEntity vehicle, boolean controlledBefore) {
        boolean controlled = canClientAuthoritativelyMoveVisibleRoot(vehicle);
        if (controlled != controlledBefore) {
            markVehicleSwitchBufferWindow(vehicle);
        }
        if (controlled && !controlledBefore) {
            startSaddleHandOver(vehicle);
        }
    }

    /**
     * Control passes to the client while the root still follows a stepped server path. The client stops reading server
     * moves for a root it controls (ClientPacketListener#handleMoveEntity), so that path ends after its queued ticks.
     */
    private void startSaddleHandOver(PacketEntity vehicle) {
        if (!hasActiveSteppedInterpolation(vehicle)) {
            return;
        }
        int lastClientTick =
                player.packetStateData.acceptedClientTick + vehicle.newPacketLocation.remainingSteppedTicks();
        if (isSaddleHandOverActiveFor(vehicle) && saddleHandOverLastClientTick >= lastClientTick) {
            return;
        }
        saddleHandOverVehicleId = vehicle.getEntityId();
        saddleHandOverLastClientTick = lastClientTick;
    }

    /**
     * The yaw the root travels at: its tickRidden turns it to the controller's yaw before LivingEntity#travel, while a
     * move sent during the hand-over carries the interpolation target's rotation.
     */
    public float saddleHandOverTravelYaw(@Nullable PacketEntity vehicle, float reportedYaw) {
        return isSaddleHandOverActiveFor(vehicle) && hasActiveSteppedInterpolation(vehicle) ? player.xRot : reportedYaw;
    }

    private boolean isSaddleHandOverActiveFor(@Nullable PacketEntity vehicle) {
        return vehicle != null
                && saddleHandOverVehicleId != null
                && saddleHandOverVehicleId == vehicle.getEntityId()
                && player.packetStateData.acceptedClientTick <= saddleHandOverLastClientTick;
    }

    /**
     * ServerboundMoveVehiclePacket#fromEntity sends Entity#getClientPositionAndRotation, which is the interpolation
     * target while the root still follows the server path; the root itself moved by its own travel from the
     * interpolated position, and AbstractInterpolationHandler#adjustInterpolationTargetFromDeltas carries that travel
     * into the target on the next tick.
     */
    private void acceptSaddleHandOverMove(PredictionResult result, @Nullable PacketEntity vehicle) {
        if (!isSaddleHandOverActiveFor(vehicle)
                || !hasActiveSteppedInterpolation(vehicle)
                || result.getSimulationContext().getStart() == null) {
            return;
        }
        Vec3 start = result.getSimulationContext().getStart();
        Vec3 report = start.add(result.getTarget());
        if (report.distanceTo(vehicle.newPacketLocation.targetPosition()) > SADDLE_HAND_OVER_REPORT_EPSILON) {
            return;
        }
        result.removeFlags(VehicleOffset.class);
        Vec3 travel = result.getAcceptedClosestToTarget();
        result.setProfilePredictionVector(travel);
        saddleHandOverTravelClientTick = player.packetStateData.acceptedClientTick;
        saddleHandOverTravel = new Vec3(travel.x, travel.y, travel.z);
        saddleHandOverPhysicalEnd = start.add(saddleHandOverTravel);
    }

    private boolean isSaddleHandOverTravelFor(PacketEntity vehicle) {
        return saddleHandOverTravel != null
                && saddleHandOverTravelClientTick == player.packetStateData.acceptedClientTick
                && saddleHandOverVehicleId != null
                && saddleHandOverVehicleId == vehicle.getEntityId();
    }

    /**
     * The movement to commit for {@code result} in place of {@code acceptedMovement}: the root's own travel when its
     * move reported the interpolation target.
     */
    public Vec3 committedVehicleMovement(PredictionResult result, Vec3 acceptedMovement) {
        PacketEntity vehicle = result == null || result.getSimulationContext() == null
                ? null
                : result.getSimulationContext().getVehicle();
        return vehicle != null && isSaddleHandOverTravelFor(vehicle) ? saddleHandOverTravel : acceptedMovement;
    }

    public void markVehicleSwitchMovementPacketBoundary() {
        markItemControlledVehicleMovementPacketBoundary();
    }

    private void markItemControlledVehicleMovementPacketBoundary() {
        PacketEntity vehicle = possibleItemControlledVehicle();
        if (vehicle == null || !hasSaddle(vehicle)) return;

        markVehicleSwitchBufferWindow(vehicle);
    }

    public boolean canOpenVehicleSwitchBufferForMovementPacket(@Nullable PacketEntity vehicle) {
        return hasUnprovenCurrentTickItemControl(vehicle) || canPossiblySwitchToItemControlThisClientTick(vehicle);
    }

    public boolean isVehicleSwitchBufferActiveFor(@Nullable PacketEntity vehicle) {
        return vehicle != null
                && isVehicleSwitchBufferActive()
                && lastVehicleSwitchBufferVehicleId != null
                && lastVehicleSwitchBufferVehicleId == vehicle.getEntityId();
    }

    public boolean forceResyncVehicleSwitchBuffer(@Nullable PacketEntity vehicle, String reason) {
        if (!isVehicleSwitchBufferActiveFor(vehicle)) {
            return false;
        }

        player.getSetbackTeleportUtil().executeForceResync(reason);
        return player.getSetbackTeleportUtil().isPendingSetback();
    }

    public void consumeVehicleSwitchPredictionOffset(PredictionResult result) {
        if (player.isBedrockMovement() || result == null || result.isTeleport()) {
            return;
        }

        PacketEntity vehicle = result.getSimulationContext() == null
                ? null
                : result.getSimulationContext().getVehicle();
        acceptSaddleHandOverMove(result, vehicle);
        if (!isVehicleSwitchBufferActiveFor(vehicle)) {
            return;
        }

        refillVehicleSwitchBuffer(result);
        if (!wouldFlagPrediction(result)) {
            return;
        }

        double offset = vehicleSwitchBufferOffset(result);
        if (offset <= 0.0D) {
            return;
        }

        resetVehicleSwitchBufferTickSpend();
        double remainingThisTick =
                Math.max(0.0D, VEHICLE_SWITCH_BUFFER_MAX_PER_CLIENT_TICK - vehicleSwitchBufferSpentThisClientTick);
        double bufferBefore = vehicleSwitchBuffer;
        double tickSpendBefore = vehicleSwitchBufferSpentThisClientTick;
        double consumed = Math.min(offset, Math.min(vehicleSwitchBuffer, remainingThisTick));
        vehicleSwitchBuffer -= consumed;
        vehicleSwitchBufferSpentThisClientTick += consumed;

        if (consumed > 0.0D) {
            debug(() -> "vehicleSwitch take buffer=" + formatDebug(bufferBefore)
                    + " -> " + formatDebug(vehicleSwitchBuffer)
                    + " tickSpend=" + formatDebug(tickSpendBefore)
                    + " -> " + formatDebug(vehicleSwitchBufferSpentThisClientTick)
                    + " needed=" + formatDebug(offset)
                    + " took=" + formatDebug(consumed)
                    + " tick=" + player.packetStateData.acceptedClientTick);
        }

        if (consumed + VEHICLE_SWITCH_BUFFER_EPSILON < offset) {
            debug(() -> "vehicleSwitch ran_out buffer=" + formatDebug(bufferBefore)
                    + " -> " + formatDebug(vehicleSwitchBuffer)
                    + " needed=" + formatDebug(offset)
                    + " took=" + formatDebug(consumed)
                    + " missing=" + formatDebug(offset - consumed)
                    + " tick=" + player.packetStateData.acceptedClientTick);
            player.getSetbackTeleportUtil().executeForceResync("vehicle-switch-buffer-exhausted");
            boolean setbackActive = player.getSetbackTeleportUtil().isPendingSetback();
            debug(() -> "vehicleSwitch resync reason=\"vehicle-switch-buffer-exhausted\" pending=" + setbackActive
                    + " tick=" + player.packetStateData.acceptedClientTick);
            if (setbackActive) {
                result.exempt();
            }
            return;
        }

        result.exempt();
    }

    @Nullable
    public PacketEntityHorse getClientVisibleHorseRoot() {
        if (entities.playerEntity.getRiding() instanceof PacketEntityHorse horse) return horse;
        PacketEntity vehicle = serverPlayerVehicle == null ? null : entities.getEntity(serverPlayerVehicle);
        return vehicle instanceof PacketEntityHorse horse && canServerPlayerVehicleBeLocalAuthoritative()
                ? horse
                : null;
    }

    public boolean canServerPlayerVehicleBeLocalAuthoritative() {
        PacketEntity vehicle = serverPlayerVehicle == null ? null : entities.getEntity(serverPlayerVehicle);
        return hasClientObservedServerVehicle()
                && vehicle != null
                && passengerIndex(vehicle) == 0
                && canLocalClientAuthoritativelyMove(vehicle);
    }

    public boolean canServerPlayerVehicleBeLocalAuthoritativeForMovementPacket(boolean fromClientTick) {
        PacketEntity vehicle = serverPlayerVehicle == null ? null : entities.getEntity(serverPlayerVehicle);
        return vehicle != null
                && passengerIndex(vehicle) == 0
                && canLocalClientAuthoritativelyMove(vehicle)
                && !(fromClientTick && hasUnprovenCurrentTickItemControl(vehicle))
                && (hasClientObservedServerVehicle() || fromClientTick);
    }

    public boolean canCurrentPlayerControlServerVehicleForClientTickMovement() {
        return canServerPlayerVehicleBeLocalAuthoritative();
    }

    /**
     * Whether the Java client's own movement tick moves this velocity root, so end-of-tick interpolation skips it and
     * VehiclePredictionRunner runs its per-tick tail instead.
     */
    public boolean isLocallyTickedVelocityRoot(@Nullable PacketEntity vehicle) {
        return !player.isBedrockMovement()
                && vehicle != null
                && vehicle == getVelocityMovementVehicle()
                && (canClientAuthoritativelyMoveVisibleRoot(vehicle)
                        || player.packetStateData.clientTickVehicleMovePacketsThisClientTick > 0
                        || player.packetStateData.localAuthoritativeVehicleMovePacketsThisClientTick > 0);
    }

    public boolean canClientAuthoritativelyMoveVisibleRoot(@Nullable PacketEntity vehicle) {
        return vehicle != null && passengerIndex(vehicle) == 0 && canLocalClientAuthoritativelyMove(vehicle);
    }

    /**
     * Whether the local player, as the client-visible first passenger, could be this vehicle's controller. Only
     * server-sent state decides this, never the client's movement packets. A saddled pig or strider counts whatever
     * item is held, because the held item is the client's choice and can trail the real slot by a tick.
     */
    public boolean mayLocalPlayerControl(@Nullable PacketEntity vehicle) {
        if (vehicle == null || vehicle.passengers.indexOf(entities.playerEntity) != 0) return false;
        return isItemControlledVehicleType(vehicle) ? hasSaddle(vehicle) : canLocalClientAuthoritativelyMove(vehicle);
    }

    public boolean canClientEchoLocalAuthoritativeMoveVehiclePacket(@Nullable PacketEntity vehicle) {
        // MCP-Reborn Entity#isLocalInstanceAuthoritative only requires the root
        // vehicle's controlling passenger to be the local player. Pig/strider
        // item control gates movement inputs, not MoveVehicle packet emission.
        return vehicle != null
                && passengerIndex(vehicle) == 0
                && !"cushion"
                        .equals(ac.cult.cultac.utils.nmsutil.EntityTypeUtil.getKey(vehicle.type)
                                .getPath());
    }

    /**
     * Whether the client must send ServerboundMoveVehiclePacket each tick. LocalPlayer#sendChanges sends it when the
     * root vehicle is Entity#isLocalInstanceAuthoritative, which needs a controlling passenger that is the local
     * player; otherwise the passenger only sends its Rot. A pig or strider is controlled only while its rider holds the
     * stick, which the client chooses, so neither is ever required to.
     */
    public boolean mustEchoMoveVehicle(@Nullable PacketEntity root) {
        return root != null
                && passengerIndex(root) == 0
                && !isItemControlledVehicleType(root)
                && canLocalClientAuthoritativelyMove(root);
    }

    private boolean canPossiblySwitchToItemControlThisClientTick(@Nullable PacketEntity vehicle) {
        if (vehicle == null || passengerIndex(vehicle) != 0 || !hasSaddle(vehicle)) {
            return false;
        }
        if (hasUnprovenCurrentTickItemControl(vehicle)) {
            return false;
        }
        if (vehicle.type == EntityTypeIds.PIG) {
            return player.getInventory()
                    .hasClientSelectableHandItem(ac.cult.cultac.utils.inventory.ItemTypes.CARROT_ON_A_STICK);
        }
        if (vehicle.type == EntityTypeIds.STRIDER) {
            return player.getInventory()
                    .hasClientSelectableHandItem(ac.cult.cultac.utils.inventory.ItemTypes.WARPED_FUNGUS_ON_A_STICK);
        }
        return false;
    }

    private boolean hasUnprovenCurrentTickItemControl(@Nullable PacketEntity vehicle) {
        return player.packetStateData.carriedItemChangedThisClientTick && isCurrentItemControlledVehicle(vehicle);
    }

    public boolean canLocalClientAuthoritativelyMoveMountedVehicle(@Nullable PacketEntity vehicle, int[] passengers) {
        return vehicle != null
                && passengers.length > 0
                && passengers[0] == player.entityID
                && canLocalClientAuthoritativelyMove(vehicle);
    }

    public boolean shouldProtocolResyncOnMount(@Nullable PacketEntity vehicle, int[] passengers) {
        return canLocalClientAuthoritativelyMoveMountedVehicle(vehicle, passengers);
    }

    public boolean hasClientObservedServerVehicle() {
        return serverPlayerVehicle != null
                && serverPlayerVehicleTransaction != null
                && player.lastTransactionReceived.get() >= serverPlayerVehicleTransaction;
    }

    public boolean hasPendingServerDismount() {
        return serverPlayerVehicle != null
                && serverPlayerVehiclePassengers == null
                && serverPlayerVehicleTransaction != null;
    }

    public boolean isServerPlayerPassengerOf(int vehicleId) {
        return serverPlayerVehicle != null
                && serverPlayerVehicle == vehicleId
                && containsLocalPlayer(serverPlayerVehiclePassengers);
    }

    /**
     * Returns whether either Cult's immediate server timeline or its
     * transaction-delayed client timeline still has the local player mounted.
     *
     * <p>The server vehicle marker is installed before the client can process a
     * mount. Conversely, entity removal clears that marker immediately, unless
     * a sent dismount of that vehicle is still pending, while the compensated
     * passenger graph cannot be ejected until the trailing
     * transaction proves that the client processed the removal. Both sides of
     * that boundary must reject ordinary non-rotation player movement.</p>
     */
    public boolean hasPlayerPassengerState() {
        return entities.playerEntity.inVehicle()
                || containsLocalPlayer(serverPlayerVehiclePassengers)
                || hasPendingServerDismount();
    }

    /**
     * Whether the client is mounted or still awaiting its server dismount.
     * Before the client processes a mount, its movement is ordinary LocalPlayer#tick movement.
     */
    public boolean hasClientPlayerPassengerState() {
        return entities.playerEntity.inVehicle() || hasPendingServerDismount();
    }

    private boolean containsLocalPlayer(@Nullable int[] passengers) {
        if (passengers == null) return false;
        for (int passenger : passengers) {
            if (passenger == player.entityID) return true;
        }
        return false;
    }

    private static boolean containsId(@Nullable int[] passengers, int entityId) {
        if (passengers == null) return false;
        for (int passenger : passengers) {
            if (passenger == entityId) return true;
        }
        return false;
    }

    public void applyClientVisibleDismount() {
        PacketEntity previousRoot = entities.playerEntity.getRiding();
        entities.playerEntity.eject();
        clearServerVehicle();
        seedStartingVelocityIfRootChanged(previousRoot);
    }

    public int passengerIndex(PacketEntity vehicle) {
        if (serverPlayerVehicle != null
                && serverPlayerVehicle == vehicle.getEntityId()
                && serverPlayerVehiclePassengers != null) {
            for (int i = 0; i < serverPlayerVehiclePassengers.length; i++) {
                if (serverPlayerVehiclePassengers[i] == player.entityID) return i;
            }
            return -1;
        }
        return vehicle.passengers.indexOf(entities.playerEntity);
    }

    public boolean applyVehiclePassengers(int vehicleId, int[] passengerIds) {
        PacketEntity previousRoot = entities.playerEntity.getRiding();
        PacketEntity vehicle = entities.getEntity(vehicleId);
        if (vehicle == null) return false;
        for (PacketEntity passenger : vehicle.passengers) {
            if (!containsId(passengerIds, passenger.getEntityId())) entities.settleIfUntrackedRider(passenger);
        }
        for (PacketEntity passenger : List.copyOf(vehicle.passengers)) passenger.eject();
        for (int passengerId : passengerIds) {
            PacketEntity passenger = entities.getEntity(passengerId);
            if (passenger == null || passenger == vehicle) continue;
            // Entity#startRiding refuses a vehicle the passenger already rides or one that rides the passenger.
            if (passenger.getRiding() == vehicle || entities.ridesIndirectly(vehicle, passenger)) continue;
            passenger.mount(vehicle);
            // The seat is placed before the client's next ride tick confirms it.
            passenger.markPositionInexact();
        }
        entities.updatePassengerPositions();
        seedStartingVelocityIfRootChanged(previousRoot);
        if (player.isBedrockMovement() && previousRoot == null && !containsLocalPlayer(passengerIds)) {
            player.getSetbackTeleportUtil().transferBedrockVehicleSetback(vehicleId);
        }
        return true;
    }

    void seedStartingVelocityIfRootChanged(@Nullable PacketEntity previousRoot) {
        PacketEntity currentRoot = entities.playerEntity.getRiding();
        if (sameRoot(previousRoot, currentRoot)) {
            return;
        }

        if (player.bedrockState != null) {
            player.bedrockState.movementCorrections.clear();
            player.checkManager
                    .getListener(ac.cult.cultac.checks.impl.bedrock.BedrockMovement.class)
                    .onVehicleMountSwitch();
        }

        markVehicleSwitchBufferWindow(currentRoot == null ? previousRoot : currentRoot);
        Vec3 rootDeltaMovement = currentRoot == null ? entities.playerEntity.deltaMovement : currentRoot.deltaMovement;
        player.checkManager.getSimulationProcessor().seedStartingVelocity(rootDeltaMovement);
        if (player.isBedrockMovement() && previousRoot != null && currentRoot == null) {
            player.getSetbackTeleportUtil().transferBedrockVehicleSetback(previousRoot.getEntityId());
        }
    }

    private void markVehicleSwitchBufferWindow(@Nullable PacketEntity vehicle) {
        lastVehicleSwitchClientTick = player.packetStateData.acceptedClientTick;
        lastVehicleSwitchBufferVehicleId = vehicle == null ? null : vehicle.getEntityId();
        resetVehicleSwitchBufferTickSpend();
    }

    private boolean isVehicleSwitchBufferActive() {
        int currentTick = player.packetStateData.acceptedClientTick;
        return lastVehicleSwitchClientTick != Integer.MIN_VALUE
                && currentTick - lastVehicleSwitchClientTick < VEHICLE_SWITCH_BUFFER_CLIENT_TICKS;
    }

    private void refillVehicleSwitchBuffer(PredictionResult result) {
        if (lastVehicleSwitchBufferRefillPrediction == result) {
            return;
        }

        lastVehicleSwitchBufferRefillPrediction = result;
        vehicleSwitchBuffer =
                Math.min(VEHICLE_SWITCH_BUFFER_MAX, vehicleSwitchBuffer + VEHICLE_SWITCH_BUFFER_REFILL_PER_PREDICTION);
    }

    private boolean wouldFlagPrediction(PredictionResult result) {
        return !result.isExempt()
                && (result.getFlagSeverity() > 0.0D || !result.getFlags().isEmpty());
    }

    private void resetVehicleSwitchBufferTickSpend() {
        int currentTick = player.packetStateData.acceptedClientTick;
        if (lastVehicleSwitchBufferSpendClientTick != currentTick) {
            lastVehicleSwitchBufferSpendClientTick = currentTick;
            vehicleSwitchBufferSpentThisClientTick = 0.0D;
        }
    }

    private double vehicleSwitchBufferOffset(PredictionResult result) {
        PacketEntity vehicle = result.getSimulationContext() == null
                ? null
                : result.getSimulationContext().getVehicle();
        if (isItemControlledVehicleType(vehicle) && result.getValidMovements() != null) {
            return result.getTarget()
                    .subtract(result.getAcceptedClosestToTarget())
                    .length();
        }
        return result.getOffset();
    }

    private String formatDebug(double value) {
        return "[" + NumFormatter.formatNumberDebug(value) + "]";
    }

    @Nullable
    private Vec3 currentExactServerControlledPosition(PacketEntity vehicle) {
        if (vehicle.clientPhysicalPosition != null && vehicle.clientPhysicalPositionExact) {
            return vehicle.clientPhysicalPosition;
        }
        if (!hasActiveInterpolationTarget(vehicle)) {
            return vehicle.desyncClientPos;
        }
        return null;
    }

    private boolean sameRoot(@Nullable PacketEntity previousRoot, @Nullable PacketEntity currentRoot) {
        if (previousRoot == null || currentRoot == null) {
            return previousRoot == currentRoot;
        }

        return previousRoot.getEntityId() == currentRoot.getEntityId();
    }

    private void snapToTrackedServerPosition(PacketEntity vehicle) {
        TrackerData tracked = entities.getTrackedEntity(vehicle.getEntityId());
        if (tracked == null || player.lastTransactionReceived.get() < tracked.getLastTransactionHung()) return;
        vehicle.setPositionRaw(
                GetBoundingBox.getPacketEntityBoundingBox(
                        player, tracked.getX(), tracked.getY(), tracked.getZ(), vehicle),
                tracked.getXRot(),
                tracked.getYRot());
    }

    @Nullable
    private PacketEntity possibleItemControlledVehicle() {
        PacketEntity vehicle = entities.playerEntity.getRiding();
        if (vehicle == null && serverPlayerVehicle != null) vehicle = entities.getEntity(serverPlayerVehicle);
        return isItemControlledVehicleType(vehicle) ? vehicle : null;
    }

    private boolean isCurrentItemControlledVehicle(@Nullable PacketEntity vehicle) {
        return isItemControlledVehicleType(vehicle) && passengerIndex(vehicle) == 0;
    }

    private boolean isItemControlledVehicleType(@Nullable PacketEntity vehicle) {
        return vehicle != null && (vehicle.type == EntityTypeIds.PIG || vehicle.type == EntityTypeIds.STRIDER);
    }

    private boolean canLocalClientAuthoritativelyMove(PacketEntity vehicle) {
        if ("cushion"
                .equals(ac.cult.cultac.utils.nmsutil.EntityTypeUtil.getKey(vehicle.type)
                        .getPath())) return false;
        if (vehicle.type == EntityTypeIds.PIG) {
            return hasSaddle(vehicle)
                    && player.getInventory()
                            .hasClientSelectedHandItem(ac.cult.cultac.utils.inventory.ItemTypes.CARROT_ON_A_STICK);
        }
        if (vehicle.type == EntityTypeIds.STRIDER) {
            return hasSaddle(vehicle)
                    && player.getInventory()
                            .hasClientSelectedHandItem(
                                    ac.cult.cultac.utils.inventory.ItemTypes.WARPED_FUNGUS_ON_A_STICK);
        }
        if (vehicle instanceof PacketEntityHorse horse) return horse.hasSaddle;
        if (vehicle instanceof PacketEntityNautilus nautilus) return nautilus.hasSaddle;
        if (vehicle instanceof PacketEntityHappyGhast happyGhast)
            return happyGhast.hasBodyArmor && !happyGhast.staysStill;
        // Callers check the controlling passenger, using the incoming list for a mount.
        // AbstractBoat is the only other getControllingPassenger override that can return
        // a player; Entity#getControllingPassenger (e.g. minecarts) and Mob's return no player.
        return EntityTypeUtil.isBoat(vehicle.type);
    }

    private boolean hasSaddle(PacketEntity vehicle) {
        return vehicle instanceof PacketEntityRideable rideable && rideable.hasSaddle;
    }

    private boolean hasActiveInterpolationTarget(PacketEntity vehicle) {
        return vehicle.newPacketLocation != null && vehicle.newPacketLocation.hasActiveInterpolationTarget();
    }

    private boolean hasActiveSteppedInterpolation(PacketEntity vehicle) {
        return hasActiveInterpolationTarget(vehicle) && vehicle.newPacketLocation.usesSteppedInterpolation();
    }
}
