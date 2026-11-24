package ac.cult.cultac.checks.impl.combat;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.anticheat.NettyScheduler.PlayerTask;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedVehicleState;
import ac.cult.cultac.utils.latency.SentRidingGraph;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import io.netty.util.concurrent.ScheduledFuture;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AllArgsConstructor;

// We will verify the player's reach against what a lower ping/more stable player would see
// By doing this we defeat a lot of ping-manipulation based reach cheats
//
// While you can manipulate ping when going towards self and going away from self
// I have never seen a cheat, even private cheats, which have accomplishes this
// Therefore, I will only patch it (and slightly hurt player experience -_-) after I see it.
//
public class FairReach extends Check implements CheckListener {

    private static final Object MOVE_APPLIED = new Object();
    // A remote player's position handler interpolates over 3 client ticks.
    private static final int PLAYER_LERP_STEPS = 3;

    final Int2ObjectOpenHashMap<FairReachEntity> targetPlayers = new Int2ObjectOpenHashMap<>();
    // Vehicles carrying a tracked player or the attacker; Entity#positionRider seats their riders from them.
    final Int2ObjectOpenHashMap<FairReachEntity> vehicles = new Int2ObjectOpenHashMap<>();
    // Where the attacker's seat or the server's teleport of it put it; read while its client does not move it itself.
    private FairReachEntity attacker;
    // The riding links each applied at the same time as the moves around it.
    private final SentRidingGraph riding = new SentRidingGraph();
    // Counts the client levels replaced so far.
    private int level;
    // Counts the sent changes getControllingPassenger can read for the attacker's root, then the last applied here
    // and the last its client answered; while one applied here is unanswered the server alone decides control.
    private int controlChanges;
    private int controlApplied;
    private int controlAnswered;
    private final SimpleCollisionBox seatBox = new SimpleCollisionBox();
    private final SimpleCollisionBox seat = new SimpleCollisionBox();

    TickingStatus playerTickingStatus = TickingStatus.ALLOWED_TO_TICK;

    int max_position_packet_jitter = 15;
    int max_ping_compensation = 300;
    double reach_leniency = 0.1;

    ScheduledFuture<?> tickStartTask = null;
    ScheduledFuture<?> tickEndTask = null;
    private final PlayerTask tickStart = new PlayerTask(player, this::tickStart);
    private final PlayerTask tickEnd = new PlayerTask(player, this::tickEnd);

    private final ClientVisibilityProcessor visibility;

    public static final CheckInfo INFO = CheckInfo.builder()
            .name("FairReach")
            .description("Hit a player who was out of reach")
            .stableKey("cult.combat.fair_reach")
            .build();

    public FairReach(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player, INFO);
        this.visibility = visibility;
    }

    @CultPacketHandler
    public void onAddEntity(
            PacketSendEvent<ClientboundAddEntity> event, CultPlayer player, ClientboundAddEntity packet) {
        // ClientLevel#addEntity removes an entity already held under the same ID.
        removeEntity(packet.entityId());
        if (packet.entityType().equals("minecraft:player")) {
            addEntity(
                    packet.entityId(),
                    packet.position().x(),
                    packet.position().y(),
                    packet.position().z());
        }
    }

    @CultPacketHandler
    public void onRemoveEntities(
            PacketSendEvent<ClientboundRemoveEntities> event, CultPlayer player, ClientboundRemoveEntities packet) {
        for (int entityId : packet.entityIds()) {
            removeEntity(entityId);
        }
    }

    /** ClientLevel#removeEntity, applied when the client handles it, in order with the moves sent before it. */
    private void removeEntity(int entityId) {
        CompensatedVehicleState sentRiding = player.compensatedEntities.vehicles;
        if (tracked(entityId) == null
                && !riding.rides(entityId)
                && riding.passengersOf(entityId) == null
                && !sentRiding.ridesAnotherEntityAsSent(entityId)
                && sentRiding.passengersAsSent(entityId) == null) return;
        schedule(new PendingRemoval(entityId, targetPlayers.get(entityId), vehicles.get(entityId)));
    }

    /** The packet that moved an entity; a teleport and a position sync snap past 64 blocks instead of lerping. */
    public enum PositionUpdate {
        RELATIVE,
        TELEPORT,
        POSITION_SYNC
    }

    public void handleEntityMove(int entityId, double x, double y, double z, PositionUpdate update) {
        if (tracked(entityId) == null) return;
        schedule(new PendingMove(entityId, x, y, z, update, false));
    }

    /**
     * A rotation-only move restarts the client's position interpolation: toward the kept target before 1.21.5, and
     * from 1.21.9 only when the rotation changed; 1.21.5 to 1.21.8 retarget it to the entity's shown position, and
     * 26.3 appends a stationary step instead.
     */
    public void handleEntityRotation(int entityId, double x, double y, double z, boolean rotationChanged) {
        ClientVersion version = player.getClientVersion();
        if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) return;
        if (version.isNewerThanOrEquals(ClientVersion.V_1_21_9) && !rotationChanged) return;
        boolean holdsShownPosition =
                version.isNewerThanOrEquals(ClientVersion.V_1_21_5) && version.isOlderThan(ClientVersion.V_1_21_9);
        if (tracked(entityId) == null) return;
        schedule(new PendingMove(entityId, x, y, z, PositionUpdate.RELATIVE, holdsShownPosition));
    }

    /**
     * A set_passengers for {@code vehicleId} naming the {@code passengers} the client holds, after the sent riding
     * graph took it, applied at {@code transaction}, the one that proves it to the client model. Each vehicle that now
     * carries a tracked player or the attacker is tracked from its last sent position.
     */
    public void handlePassengers(int vehicleId, int[] passengers, int transaction) {
        CompensatedVehicleState sentRiding = player.compensatedEntities.vehicles;
        if (carriesTrackedPlayer(sentRiding, vehicleId)) {
            // Each keeps its own position once its chain splits.
            int root = sentRiding.rootAsSent(vehicleId);
            for (int vehicle = vehicleId; ; vehicle = sentRiding.vehicleAsSent(vehicle)) {
                trackVehicle(vehicle);
                if (vehicle == root) break;
            }
        }
        if (attacker == null && contains(passengers, player.entityID)) {
            attacker = new FairReachEntity(player.entityID, player.x, player.y, player.z, 0);
        }
        schedule(new PendingPassengers(vehicleId, passengers), transaction);
    }

    /** A player_position for the attacker itself, once a riding change has carried it. */
    public void handleSelfTeleport(double x, double y, double z, RelativeFlag relatives) {
        if (attacker == null) return;
        schedule(new PendingTeleport(x, y, z, relatives));
    }

    /** A sent change to the attacker's hands, which Pig and Strider#getControllingPassenger read (Player#isHolding). */
    public void handleHandChange() {
        int root = player.compensatedEntities.vehicles.rootAsSent(player.entityID);
        TrackerData sent = root == player.entityID ? null : player.compensatedEntities.getTrackedEntity(root);
        if (sent != null
                && (sent.getEntityType() == EntityTypeIds.PIG || sent.getEntityType() == EntityTypeIds.STRIDER))
            controlChange();
    }

    /**
     * Equipment or entity data sent for {@code entityId}. A root's saddle, harness, still timeout, health, age and
     * tame state decide whether its first passenger controls it.
     */
    public void handleEntityState(int entityId) {
        if (entityId != player.entityID
                && player.compensatedEntities.vehicles.rootAsSent(player.entityID) == entityId) controlChange();
    }

    /** Sent entity type tags, which Mob#isSaddled reads through Equippable#canBeEquippedBy. */
    public void handleEntityTags() {
        if (player.compensatedEntities.vehicles.rootAsSent(player.entityID) != player.entityID) controlChange();
    }

    private void controlChange() {
        int change = ++controlChanges;
        schedule(new PendingControl(change));
        player.latencyUtils.addRealTimeTaskNext(() -> controlAnswered = change);
    }

    private void trackVehicle(int vehicleId) {
        if (tracked(vehicleId) != null) return;
        TrackerData sent = player.compensatedEntities.getTrackedEntity(vehicleId);
        if (sent == null) return;
        PacketEntity entity = player.compensatedEntities.getEntity(vehicleId);
        int steps =
                entity == null ? PLAYER_LERP_STEPS : ReachInterpolationData.vanillaInterpolationStepsFor(player, entity);
        vehicles.put(vehicleId, new FairReachEntity(vehicleId, sent.getX(), sent.getY(), sent.getZ(), steps));
    }

    private boolean carriesTrackedPlayer(CompensatedVehicleState sentRiding, int vehicleId) {
        int[] passengers = sentRiding.passengersAsSent(vehicleId);
        if (passengers == null) return false;
        for (int passenger : passengers) {
            if (passenger == player.entityID
                    || targetPlayers.containsKey(passenger)
                    || carriesTrackedPlayer(sentRiding, passenger)) return true;
        }
        return false;
    }

    private FairReachEntity tracked(int entityId) {
        if (entityId == player.entityID) return attacker;
        FairReachEntity entity = targetPlayers.get(entityId);
        return entity != null ? entity : vehicles.get(entityId);
    }

    /**
     * ClientPacketListener#handleSetEntityPassengersPacket: the vehicle ejects its passengers, which keep the seat they
     * last held, and each listed one rides it from its next tick on (Entity#rideTick).
     */
    private void seatPassengers(int vehicleId, int[] passengers) {
        int[] previous = riding.passengersOf(vehicleId);
        if (previous != null) {
            for (int passenger : previous) {
                if (!contains(passengers, passenger)) dismount(passenger);
            }
        }
        for (int passenger : passengers) {
            if (passenger == player.entityID && !riding.rides(passenger)) {
                // The attacker mounts from where its own movement left it.
                if (attacker == null) attacker = new FairReachEntity(passenger, player.x, player.y, player.z, 0);
                else attacker.snapTo(player.x, player.y, player.z);
            }
            FairReachEntity entity = tracked(passenger);
            if (entity == null || (riding.rides(passenger) && riding.vehicleOf(passenger) == vehicleId)) continue;
            dismount(passenger);
            entity.awaitingSeat = true;
        }
        riding.setPassengers(vehicleId, passengers);
    }

    private static boolean contains(int[] ids, int id) {
        for (int each : ids) {
            if (each == id) return true;
        }
        return false;
    }

    /** Entity#stopRiding moves nothing, so the passenger stays where its last ride tick seated it. */
    private void dismount(int entityId) {
        FairReachEntity entity = tracked(entityId);
        if (entity == null || !riding.rides(entityId)) return;
        locate(entityId);
        entity.snapTo(seat.minX, seat.minY, seat.minZ);
    }

    /**
     * Writes where the client shows {@code entityId} into seat.minX/minY/minZ: on the seat Entity#positionRider gives
     * it on its vehicle, or where it is itself. False when neither is known.
     */
    private boolean locate(int entityId) {
        FairReachEntity own = tracked(entityId);
        if (riding.rides(entityId) && (own == null || !own.awaitingSeat)) {
            int vehicleId = riding.vehicleOf(entityId);
            if (locate(vehicleId)) {
                PacketEntity vehicle = player.compensatedEntities.getEntity(vehicleId);
                PacketEntity passenger = player.compensatedEntities.getEntity(entityId);
                // Past its deadline the client may not hold the vehicle's spawn yet; the rider is then at the vehicle.
                if (vehicle == null || passenger == null) return true;
                int[] passengers = riding.passengersOf(vehicleId);
                int index = 0;
                while (passengers[index] != entityId) index++;
                seatBox.set(seat.minX, seat.minY, seat.minZ, seat.minX, seat.minY, seat.minZ);
                BoundingBoxSize.ridingPosition(vehicle, passenger, player, seatBox, index, passengers.length, seat);
                return true;
            }
        }
        if (own == null) return false;
        seat.set(own.currentX, own.currentY, own.currentZ, own.currentX, own.currentY, own.currentZ);
        return true;
    }

    /**
     * Where the attacker's client attacks from, riding as the applied links have it. A root it moves itself is where
     * its own vehicle moves put it (LocalPlayer#sendChanges); a vehicle the server moves carries it as the sent moves
     * of that vehicle do; off its vehicle it is where the server's own teleport of it put it.
     */
    private List<Vec3> attackOrigins() {
        PacketEntity vehicle = player.compensatedEntities.getSelf().getRiding();
        boolean rides = riding.rides(player.entityID);
        if (attacker == null || (vehicle == null && !rides) || (rides && drivesItself(vehicle))) {
            return ReachCheck.attackOrigins(player);
        }
        locate(player.entityID);
        return List.of(new Vec3(seat.minX, seat.minY, seat.minZ));
    }

    /**
     * Whether the attacker controls the root it rides, both as the applied links and changes and as its client has
     * it.
     */
    private boolean drivesItself(PacketEntity vehicle) {
        if (vehicle == null || vehicle.getRiding() != null) return false;
        int vehicleId = vehicle.getEntityId();
        return controlAnswered >= controlApplied
                && riding.vehicleOf(player.entityID) == vehicleId
                && !riding.rides(vehicleId)
                && riding.passengersOf(vehicleId)[0] == player.entityID
                && player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(vehicle);
    }

    private void schedule(PendingChange move) {
        schedule(move, player.lastTransactionSent.get() + 1);
    }

    private void schedule(PendingChange move, int transaction) {
        // Ensure the client has received this before MAX_PING_TIME, or apply them sooner if client responds in time
        player.latencyUtils.addRealTimeTask(transaction, move);
        if (move.get() == MOVE_APPLIED) return;
        ScheduledFuture<?> deadline = player.user
                .getPacketExecutor()
                .schedule((Callable<Void>) move, max_ping_compensation, TimeUnit.MILLISECONDS);
        // A receipt, or an immediate deadline, can win before schedule returns its future.
        if (!move.compareAndSet(null, deadline)) deadline.cancel(false);
    }

    /**
     * One entity move or riding change, applied by whichever of its transaction or its deadline comes first. The
     * deadline runs it as a {@link Callable} through {@link CultPlayer#runSafely}. The atomic state is null until its
     * deadline is installed, that future while pending, or MOVE_APPLIED. A receipt cancels the deadline without
     * leaving a no-op model task behind; a deadline already dequeued still observes the same exact-once guard.
     */
    private abstract class PendingChange extends AtomicReference<Object> implements Runnable, Callable<Void> {
        private final int level = FairReach.this.level;

        @Override
        public void run() {
            Object previous = getAndSet(MOVE_APPLIED);
            if (previous == MOVE_APPLIED) return;
            if (previous instanceof ScheduledFuture<?> deadline) deadline.cancel(false);
            // A change sent before the level was replaced belongs to the old level.
            if (level == FairReach.this.level) apply();
        }

        @Override
        public Void call() {
            if (get() != MOVE_APPLIED) player.runSafely(this);
            return null;
        }

        abstract void apply();
    }

    private final class PendingMove extends PendingChange {
        private final int entityId;
        private final double x;
        private final double y;
        private final double z;
        private final PositionUpdate update;
        private final boolean holdsShownPosition;

        private PendingMove(
                int entityId, double x, double y, double z, PositionUpdate update, boolean holdsShownPosition) {
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.update = update;
            this.holdsShownPosition = holdsShownPosition;
        }

        @Override
        void apply() {
            applyChanges(entityId, x, y, z, update, holdsShownPosition);
        }
    }

    /** Entity#setRemoved stops the entity riding and leaves each of its passengers on the seat it last held. */
    private final class PendingRemoval extends PendingChange {
        private final int entityId;
        private final FairReachEntity target;
        private final FairReachEntity vehicle;

        private PendingRemoval(int entityId, FairReachEntity target, FairReachEntity vehicle) {
            this.entityId = entityId;
            this.target = target;
            this.vehicle = vehicle;
        }

        @Override
        void apply() {
            int[] passengers = riding.passengersOf(entityId);
            if (passengers != null) {
                for (int passenger : passengers) dismount(passenger);
            }
            riding.remove(entityId);
            // An entity added again under this ID since stays.
            if (target != null) targetPlayers.remove(entityId, target);
            if (vehicle != null) vehicles.remove(entityId, vehicle);
        }
    }

    /** ClientPacketListener#handleMovePlayer places the player only while it rides nothing. */
    private final class PendingTeleport extends PendingChange {
        private final double x;
        private final double y;
        private final double z;
        private final RelativeFlag relatives;

        private PendingTeleport(double x, double y, double z, RelativeFlag relatives) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.relatives = relatives;
        }

        @Override
        void apply() {
            if (attacker == null || riding.rides(player.entityID)) return;
            attacker.snapTo(
                    relatives.isSet(RelativeFlag.X.getMask()) ? attacker.currentX + x : x,
                    relatives.isSet(RelativeFlag.Y.getMask()) ? attacker.currentY + y : y,
                    relatives.isSet(RelativeFlag.Z.getMask()) ? attacker.currentZ + z : z);
        }
    }

    private final class PendingControl extends PendingChange {
        private final int change;

        private PendingControl(int change) {
            this.change = change;
        }

        @Override
        void apply() {
            controlApplied = change;
        }
    }

    private final class PendingPassengers extends PendingChange {
        private final int vehicleId;
        private final int[] passengers;

        private PendingPassengers(int vehicleId, int[] passengers) {
            this.vehicleId = vehicleId;
            this.passengers = passengers;
        }

        @Override
        void apply() {
            seatPassengers(vehicleId, passengers);
        }
    }

    private void addEntity(int entityId, double x, double y, double z) {
        targetPlayers.put(entityId, new FairReachEntity(entityId, x, y, z, PLAYER_LERP_STEPS));
    }

    private void handleInteract(final PacketReceiveEvent event, ServerboundInteract attack) {
        // The server entity position will temporarily desync from the player
        // If it exceeds the player's vanilla reach plus the configured leniency from the optimal
        // reach angle, we will cancel the hit
        if (!player.isDisabled() && attack != null) {

            FairReachEntity target = targetPlayers.get(attack.entityId());

            if (target == null) return;
            if (attack.action() != InteractAction.ATTACK) return;
            // The client seats every rider of its own vehicle from the vehicle it moves itself.
            if (riding.rootOf(attack.entityId()) == riding.rootOf(player.entityID)) return;

            double maxReach = AttackReach.capture(player, visibility).maxClosestDistance() + reach_leniency;
            if (target.getMinDistanceForReach() > maxReach && mayModifyPackets()) {
                player.onPacketCancel();
                event.setCancelled(true);
            }
        }
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        handleInteract(event, packet);
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        // 15 ms is both min and max both ways, but...
        // We will force the player to tick at least every 50 ms + 15 ms
        // Ticks before 50 ms means we tick at 50 - 15 ms
        // Ticks after 50 ms means we have already ticked
        if (TickingStatus.ALLOWED_TO_TICK == playerTickingStatus) {
            tick();
        }
    }

    private void tick() {
        // The player ticked too soon, we will wait until they may tick again
        if (playerTickingStatus == TickingStatus.BEFORE_VALID_TICK
                || playerTickingStatus == TickingStatus.TICKED_BEFORE_MINIMUM) {
            playerTickingStatus = TickingStatus.TICKED_BEFORE_MINIMUM;
            return;
        }

        // Do the tick
        for (FairReachEntity entity : targetPlayers.values()) {
            entity.tick();
        }
        for (FairReachEntity entity : vehicles.values()) {
            entity.tick();
        }
        if (attacker != null) attacker.tick();

        // Prepare for the next tick, however, it is too soon for the next tick
        playerTickingStatus = TickingStatus.BEFORE_VALID_TICK;

        if (this.tickStartTask != null) this.tickStartTask.cancel(false);
        if (this.tickEndTask != null) this.tickEndTask.cancel(false);

        this.tickStartTask = player.nettyScheduler.runTaskInMs(tickStart, 50 - max_position_packet_jitter);
        this.tickEndTask = player.nettyScheduler.runTaskInMs(tickEnd, 50 + max_position_packet_jitter);
    }

    private void tickStart() {
        // If the player ticked too soon... tick, and restart this process
        // Otherwise, prepare for a valid tick
        if (TickingStatus.TICKED_BEFORE_MINIMUM == playerTickingStatus) {
            playerTickingStatus = TickingStatus.ALLOWED_TO_TICK;
            tick();
        } else {
            playerTickingStatus = TickingStatus.ALLOWED_TO_TICK;
        }
    }

    private void tickEnd() {
        // The player missed their chance at a valid tick, tick anyway for them
        if (TickingStatus.ALLOWED_TO_TICK == playerTickingStatus) {
            tick();
        }
    }

    private void applyChanges(
            int entityId, double x, double y, double z, PositionUpdate update, boolean holdsShownPosition) {
        FairReachEntity target = tracked(entityId);
        if (target == null) return;
        if (holdsShownPosition) {
            target.setTarget(target.currentX, target.currentY, target.currentZ);
        } else if (snaps(target, update, x, y, z)) {
            target.snapTo(x, y, z);
        } else {
            target.setTarget(x, y, z);
        }
    }

    /**
     * ClientPacketListener#setValuesFromPositionPacket measures a teleport from the lerp target while one is active,
     * on every version; #handleEntityPositionSync measures from the shown position.
     */
    private boolean snaps(FairReachEntity target, PositionUpdate update, double x, double y, double z) {
        if (update == PositionUpdate.RELATIVE) return false;
        boolean fromLerpTarget = update == PositionUpdate.TELEPORT && target.lerpTicks > 0;
        double dx = x - (fromLerpTarget ? target.targetX : target.currentX);
        double dy = y - (fromLerpTarget ? target.targetY : target.currentY);
        double dz = z - (fromLerpTarget ? target.targetZ : target.currentZ);
        return dx * dx + dy * dy + dz * dz > 4096.0;
    }

    /** A replacement client level holds none of the old level's entities. */
    public void clearLevel() {
        level++;
        targetPlayers.clear();
        vehicles.clear();
        riding.clear();
        attacker = null;
    }

    public void onPlayerQuit() {
        if (this.tickStartTask != null) this.tickStartTask.cancel(true);
        if (this.tickEndTask != null) this.tickEndTask.cancel(true);
    }

    @Override
    public void reload() {
        super.reload();
        max_position_packet_jitter = getConfig().getIntElse("cult.checks.fair-reach.max-position-packet-jitter-ms", 15);
        max_ping_compensation = getConfig().getIntElse("cult.checks.fair-reach.max-ping-compensation-ms", 300);
        reach_leniency = getConfig().getDoubleElse("cult.checks.fair-reach.reach-leniency", 0.1);
    }

    enum TickingStatus {
        TICKED_BEFORE_MINIMUM,
        ALLOWED_TO_TICK,
        BEFORE_VALID_TICK
    }

    @AllArgsConstructor
    static class PendingEntityMove {
        int entityId;
        double x;
        double y;
        double z;
    }

    class FairReachEntity {
        int entityId;
        // The position the client shows.
        double currentX;
        double currentY;
        double currentZ;
        // The position the client interpolates toward.
        double targetX;
        double targetY;
        double targetZ;
        int lerpTicks = 0;
        private final int steps;
        // Entity#rideTick seats a new passenger from its next tick on.
        boolean awaitingSeat;

        private FairReachEntity(int entityId, double x, double y, double z, int steps) {
            this.entityId = entityId;
            this.steps = steps;
            this.currentX = x;
            this.currentY = y;
            this.currentZ = z;
            this.targetX = x;
            this.targetY = y;
            this.targetZ = z;
        }

        private void setTarget(double x, double y, double z) {
            this.targetX = x;
            this.targetY = y;
            this.targetZ = z;
            this.lerpTicks = steps;
        }

        private void snapTo(double x, double y, double z) {
            this.currentX = x;
            this.currentY = y;
            this.currentZ = z;
            this.targetX = x;
            this.targetY = y;
            this.targetZ = z;
            this.lerpTicks = 0;
        }

        private void tick() {
            awaitingSeat = false;
            if (lerpTicks > 0) {
                double scale = 1d / lerpTicks;
                // Each axis reads only its own coordinates, so stepping them in turn is exact.
                currentX = currentX + (targetX - currentX) * scale;
                currentY = currentY + (targetY - currentY) * scale;
                currentZ = currentZ + (targetZ - currentZ) * scale;
                lerpTicks--;
            }
        }

        private double getMinDistanceForReach() {
            PacketEntity trackedEntity = player.compensatedEntities.getEntity(entityId);
            float width = trackedEntity == null ? 0.6f : BoundingBoxSize.getReachWidth(player, trackedEntity);
            float height = trackedEntity == null ? 1.8f : BoundingBoxSize.getReachHeight(player, trackedEntity);
            locate(entityId);
            SimpleCollisionBox thisEntityBox =
                    GetBoundingBox.getBoundingBoxFromPosAndSize(seat.minX, seat.minY, seat.minZ, width, height);

            double lowest = Double.MAX_VALUE;
            for (Vec3 from : attackOrigins()) {
                lowest = Math.min(
                        lowest, ReachUtils.getMinReachToBox(player, thisEntityBox.copy(), from.x, from.y, from.z));
            }
            return lowest;
        }
    }
}
