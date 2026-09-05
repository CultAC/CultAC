package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
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
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class CompensatedVehicleState implements Debuggable {
    private static final String DEBUG_NAME = "Buffer";
    private static final int VEHICLE_SWITCH_BUFFER_CLIENT_TICKS = 5;
    private static final double VEHICLE_SWITCH_BUFFER_MAX = 0.20D;
    private static final double VEHICLE_SWITCH_BUFFER_REFILL_PER_PREDICTION = 0.01D;
    private static final double VEHICLE_SWITCH_BUFFER_MAX_PER_CLIENT_TICK = 0.05D;
    private static final double VEHICLE_SWITCH_BUFFER_EPSILON = 1.0E-9D;

    private final CultPlayer player;
    private final CompensatedEntities entities;
    private double vehicleSwitchBuffer;
    private double vehicleSwitchBufferSpentThisClientTick;
    private int lastVehicleSwitchClientTick = Integer.MIN_VALUE;
    private int lastVehicleSwitchBufferSpendClientTick = Integer.MIN_VALUE;
    @Nullable private Integer lastVehicleSwitchBufferVehicleId;
    @Nullable private PredictionResult lastVehicleSwitchBufferRefillPrediction;

    @Nullable public Integer serverPlayerVehicle;
    @Nullable public int[] serverPlayerVehiclePassengers;
    @Nullable public Integer serverPlayerVehicleTransaction;

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
        if (root != null
                && !canClientAuthoritativelyMoveVisibleRoot(root)
                && !canServerPlayerVehicleBeLocalAuthoritative()
                && !hasActiveInterpolationTarget(root)) {
            snapToTrackedServerPosition(root);
        }

        entities.updatePassengerPositions();
        Vec3 provenPassengerDelta = player.packetStateData.lastPacketProvenVehiclePhysicalMovement;
        if (provenPassengerDelta != null && entities.playerEntity.getRiding() != null) {
            entities.playerEntity.deltaMovement = provenPassengerDelta;
        }
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
        if (vehicle == null || vehicle != getVelocityMovementVehicle()) return false;
        vehicle.deltaMovement = velocity;
        player.checkManager.getSimulationProcessor().seedVehicleStartingVelocity(velocity);
        return true;
    }

    public void applyAcceptedVehicleTeleportEntityState(int entityId, Vec3 position, float yaw, float pitch,
                                                        @Nullable Boolean onGround, Vec3 deltaMovement, boolean interpolates) {
        PacketEntity entity = entities.getEntity(entityId);
        if (entity == null) return;

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
        }

        if (entity != null && entity == getVelocityMovementVehicle()) {
            player.checkManager.getSimulationProcessor().seedVehicleStartingVelocity(deltaMovement);
        }
    }

    public void applyAcceptedVehiclePositionState(int entityId, Vec3 position, float yaw, float pitch,
                                                  Vec3 deltaMovement) {
        PacketEntity entity = entities.getEntity(entityId);
        if (entity == null) return;
        entity.setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, entity), yaw, pitch);
        if (entity == getVelocityMovementVehicle()) {
            entity.deltaMovement = deltaMovement;
        }
    }

    public void applyAcceptedVehiclePositionState(int entityId, Vec3 position, float yaw, float pitch,
                                                  Vec3 deltaMovement, boolean onGround) {
        applyAcceptedVehiclePositionState(entityId, position, yaw, pitch, deltaMovement);
        PacketEntity entity = entities.getEntity(entityId);
        if (entity != null) entity.onGround = onGround;
    }

    public void applyVehiclePacketPosition(PacketEntity vehicle, Vec3 to, boolean onGround,
                                           float physicalYaw, float physicalPitch) {
        if (vehicle == null) {
            return;
        }

        vehicle.setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(player, to.x, to.y, to.z, vehicle),
                physicalYaw, physicalPitch);
        vehicle.onGround = onGround;
    }

    public void setServerVehicle(int vehicleId, int[] passengers, int transaction) {
        PacketEntity vehicle = entities.getEntity(vehicleId);
        if (serverPlayerVehicle == null || serverPlayerVehicle != vehicleId) {
            player.getSetbackTeleportUtil().clearVehicleTeleports();
        }
        serverPlayerVehicle = vehicleId;
        serverPlayerVehiclePassengers = passengers.clone();
        serverPlayerVehicleTransaction = transaction;
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

    public void markVehicleSwitchMovementPacketBoundary() {
        markItemControlledVehicleMovementPacketBoundary();
    }

    private void markItemControlledVehicleMovementPacketBoundary() {
        PacketEntity vehicle = possibleItemControlledVehicle();
        if (vehicle == null || !hasSaddle(vehicle)) return;

        markVehicleSwitchBufferWindow(vehicle);
    }

    public boolean canOpenVehicleSwitchBufferForMovementPacket(@Nullable PacketEntity vehicle) {
        return hasUnprovenCurrentTickItemControl(vehicle)
                || canPossiblySwitchToItemControlThisClientTick(vehicle);
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
        if (result == null || result.isTeleport()) {
            return;
        }

        PacketEntity vehicle = result.getSimulationContext() == null ? null : result.getSimulationContext().getVehicle();
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
        double remainingThisTick = Math.max(0.0D,
                VEHICLE_SWITCH_BUFFER_MAX_PER_CLIENT_TICK - vehicleSwitchBufferSpentThisClientTick);
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
            debug(() -> "vehicleSwitch resync reason=\"vehicle-switch-buffer-exhausted\" pending="
                    + setbackActive + " tick=" + player.packetStateData.acceptedClientTick);
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
        return vehicle instanceof PacketEntityHorse horse && canServerPlayerVehicleBeLocalAuthoritative() ? horse : null;
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

    public boolean canClientAuthoritativelyMoveVisibleRoot(@Nullable PacketEntity vehicle) {
        return vehicle != null && passengerIndex(vehicle) == 0 && canLocalClientAuthoritativelyMove(vehicle);
    }

    public boolean canClientEchoLocalAuthoritativeMoveVehiclePacket(@Nullable PacketEntity vehicle) {
        // MCP-Reborn Entity#isLocalInstanceAuthoritative only requires the root
        // vehicle's controlling passenger to be the local player. Pig/strider
        // item control gates movement inputs, not MoveVehicle packet emission.
        return vehicle != null && passengerIndex(vehicle) == 0;
    }

    private boolean canPossiblySwitchToItemControlThisClientTick(@Nullable PacketEntity vehicle) {
        if (vehicle == null || passengerIndex(vehicle) != 0 || !hasSaddle(vehicle)) {
            return false;
        }
        if (hasUnprovenCurrentTickItemControl(vehicle)) {
            return false;
        }
        if (vehicle.type == EntityTypesCompat.PIG) {
            return player.getInventory().hasClientSelectableHandItem(Material.CARROT_ON_A_STICK);
        }
        if (vehicle.type == EntityTypesCompat.STRIDER) {
            return player.getInventory().hasClientSelectableHandItem(Material.WARPED_FUNGUS_ON_A_STICK);
        }
        return false;
    }

    private boolean hasUnprovenCurrentTickItemControl(@Nullable PacketEntity vehicle) {
        return player.packetStateData.carriedItemChangedThisClientTick
                && isCurrentItemControlledVehicle(vehicle);
    }

    public boolean canLocalClientAuthoritativelyMoveMountedVehicle(@Nullable PacketEntity vehicle, int[] passengers) {
        return vehicle != null && passengers.length > 0 && passengers[0] == player.entityID && canLocalClientAuthoritativelyMove(vehicle);
    }

    public boolean shouldProtocolResyncOnMount(@Nullable PacketEntity vehicle, int[] passengers) {
        return canLocalClientAuthoritativelyMoveMountedVehicle(vehicle, passengers)
                && !vehicle.isMinecart();
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
     * mount. Conversely, entity removal clears that marker immediately while
     * the compensated passenger graph cannot be ejected until the trailing
     * transaction proves that the client processed the removal. Both sides of
     * that boundary must reject ordinary non-rotation player movement.</p>
     */
    public boolean hasPlayerPassengerState() {
        return entities.playerEntity.inVehicle()
                || containsLocalPlayer(serverPlayerVehiclePassengers)
                || hasPendingServerDismount();
    }

    private boolean containsLocalPlayer(@Nullable int[] passengers) {
        if (passengers == null) return false;
        for (int passenger : passengers) {
            if (passenger == player.entityID) return true;
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
        if (serverPlayerVehicle != null && serverPlayerVehicle == vehicle.getEntityId() && serverPlayerVehiclePassengers != null) {
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
        for (PacketEntity passenger : List.copyOf(vehicle.passengers)) passenger.eject();
        for (int passengerId : passengerIds) {
            PacketEntity passenger = entities.getEntity(passengerId);
            if (passenger != null && passenger != vehicle) passenger.mount(vehicle);
        }
        entities.updatePassengerPositions();
        seedStartingVelocityIfRootChanged(previousRoot);
        return true;
    }

    private void seedStartingVelocityIfRootChanged(@Nullable PacketEntity previousRoot) {
        PacketEntity currentRoot = entities.playerEntity.getRiding();
        if (sameRoot(previousRoot, currentRoot)) {
            return;
        }

        markVehicleSwitchBufferWindow(currentRoot == null ? previousRoot : currentRoot);
        Vec3 rootDeltaMovement = currentRoot == null
                ? entities.playerEntity.deltaMovement
                : currentRoot.deltaMovement;
        player.checkManager.getSimulationProcessor().seedStartingVelocity(rootDeltaMovement);
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
        vehicleSwitchBuffer = Math.min(VEHICLE_SWITCH_BUFFER_MAX,
                vehicleSwitchBuffer + VEHICLE_SWITCH_BUFFER_REFILL_PER_PREDICTION);
    }

    private boolean wouldFlagPrediction(PredictionResult result) {
        return !result.isExempt() && (result.getFlagSeverity() > 0.0D || !result.getFlags().isEmpty());
    }

    private void resetVehicleSwitchBufferTickSpend() {
        int currentTick = player.packetStateData.acceptedClientTick;
        if (lastVehicleSwitchBufferSpendClientTick != currentTick) {
            lastVehicleSwitchBufferSpendClientTick = currentTick;
            vehicleSwitchBufferSpentThisClientTick = 0.0D;
        }
    }

    private double vehicleSwitchBufferOffset(PredictionResult result) {
        PacketEntity vehicle = result.getSimulationContext() == null ? null : result.getSimulationContext().getVehicle();
        if (isItemControlledVehicleType(vehicle) && result.getValidMovements() != null) {
            return result.getTarget().subtract(result.getAcceptedClosestToTarget()).length();
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
        vehicle.setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(player,
                tracked.getX(), tracked.getY(), tracked.getZ(), vehicle), tracked.getXRot(), tracked.getYRot());
    }

    @Nullable
    private PacketEntity possibleItemControlledVehicle() {
        PacketEntity vehicle = entities.playerEntity.getRiding();
        if (vehicle == null && serverPlayerVehicle != null) vehicle = entities.getEntity(serverPlayerVehicle);
        return isItemControlledVehicleType(vehicle) ? vehicle : null;
    }

    private boolean isCurrentItemControlledVehicle(@Nullable PacketEntity vehicle) {
        return isItemControlledVehicleType(vehicle)
                && passengerIndex(vehicle) == 0;
    }

    private boolean isItemControlledVehicleType(@Nullable PacketEntity vehicle) {
        return vehicle != null && (vehicle.type == EntityTypesCompat.PIG || vehicle.type == EntityTypesCompat.STRIDER);
    }

    private boolean canLocalClientAuthoritativelyMove(PacketEntity vehicle) {
        if (vehicle.type == EntityTypesCompat.PIG) {
            return hasSaddle(vehicle) && player.getInventory().hasClientSelectedHandItem(Material.CARROT_ON_A_STICK);
        }
        if (vehicle.type == EntityTypesCompat.STRIDER) {
            return hasSaddle(vehicle) && player.getInventory().hasClientSelectedHandItem(Material.WARPED_FUNGUS_ON_A_STICK);
        }
        if (vehicle instanceof PacketEntityHorse horse) return horse.hasSaddle;
        if (vehicle instanceof PacketEntityNautilus nautilus) return nautilus.hasSaddle;
        if (vehicle instanceof PacketEntityHappyGhast happyGhast) return happyGhast.hasBodyArmor && !happyGhast.staysStill;
        return passengerIndex(vehicle) == 0;
    }

    private boolean hasSaddle(PacketEntity vehicle) {
        return vehicle instanceof PacketEntityRideable rideable && rideable.hasSaddle;
    }

    private boolean hasActiveInterpolationTarget(PacketEntity vehicle) {
        return vehicle.newPacketLocation != null && vehicle.newPacketLocation.hasActiveInterpolationTarget();
    }

}
