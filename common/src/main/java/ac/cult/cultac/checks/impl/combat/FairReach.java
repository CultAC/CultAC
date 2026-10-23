package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.anticheat.NettyScheduler.PlayerTask;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import io.netty.util.concurrent.ScheduledFuture;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AllArgsConstructor;

// We will verify the player's reach against what a lower ping/more stable player would see
// By doing this we defeat a lot of ping-manipulation based reach cheats
//
// While you can manipulate ping when going towards self and going away from self
// I have never seen a cheat, even private cheats, which have accomplishes this
// Therefore, I will only patch it (and slightly hurt player experience -_-) after I see it.
//
public class FairReach extends Check implements CheckListener {

    Map<Integer, FairReachEntity> targetPlayers = new Int2ObjectOpenHashMap<>();

    TickingStatus playerTickingStatus = TickingStatus.ALLOWED_TO_TICK;

    int max_position_packet_jitter = 15;
    int max_ping_compensation = 300;
    double reach_leniency = 0.1;

    ScheduledFuture<?> tickStartTask = null;
    ScheduledFuture<?> tickEndTask = null;
    private final PlayerTask tickStart = new PlayerTask(player, this::tickStart);
    private final PlayerTask tickEnd = new PlayerTask(player, this::tickEnd);

    private final ClientVisibilityProcessor visibility;

    public FairReach(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(
                player,
                CheckInfo.builder()
                        .name("FairReach")
                        .stableKey("cult.combat.fair_reach")
                        .build());
        this.visibility = visibility;
    }

    @CultPacketHandler
    public void onAddEntity(
            PacketSendEvent<ClientboundAddEntity> event, CultPlayer player, ClientboundAddEntity packet) {
        if (packet.entityType().equals("minecraft:player")) {
            addEntity(
                    packet.entityId(),
                    new Vec3(
                            packet.position().x(),
                            packet.position().y(),
                            packet.position().z()));
        }
    }

    @CultPacketHandler
    public void onRemoveEntities(
            PacketSendEvent<ClientboundRemoveEntities> event, CultPlayer player, ClientboundRemoveEntities packet) {
        // Server won't process destroyed entity hits anyway... might as well remove them immediately
        for (int entityId : packet.entityIds()) {
            targetPlayers.remove(entityId);
        }
    }

    /** The packet that moved an entity; a teleport and a position sync snap past 64 blocks instead of lerping. */
    public enum PositionUpdate {
        RELATIVE,
        TELEPORT,
        POSITION_SYNC
    }

    public void handleEntityMove(int entityId, double x, double y, double z, PositionUpdate update) {
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
        schedule(new PendingMove(entityId, x, y, z, PositionUpdate.RELATIVE, holdsShownPosition));
    }

    private void schedule(PendingMove move) {
        // Ensure the client has received this before MAX_PING_TIME, or apply them sooner if client responds in time
        player.latencyUtils.addRealTimeTaskNext(move);
        player.user.getPacketExecutor().schedule((Callable<Void>) move, max_ping_compensation, TimeUnit.MILLISECONDS);
    }

    /**
     * One entity move, applied by whichever of its transaction or its deadline comes first. The deadline runs it as a
     * {@link Callable} through {@link CultPlayer#runSafely}.
     */
    private final class PendingMove extends AtomicBoolean implements Runnable, Callable<Void> {
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
        public void run() {
            applyChanges(entityId, x, y, z, update, holdsShownPosition, this);
        }

        @Override
        public Void call() {
            player.runSafely(this);
            return null;
        }
    }

    private void addEntity(int entityId, Vec3 spawnPosition) {
        targetPlayers.put(entityId, new FairReachEntity(entityId, spawnPosition));
    }

    private void handleInteract(final PacketReceiveEvent event, ServerboundInteract attack) {
        // The server entity position will temporarily desync from the player
        // If it exceeds the player's vanilla reach plus the configured leniency from the optimal
        // reach angle, we will cancel the hit
        if (!player.isDisabled() && attack != null) {

            FairReachEntity target = targetPlayers.get(attack.entityId());
            PacketEntity reachTarget = player.compensatedEntities.getEntity(attack.entityId());

            if (player.compensatedEntities.getSelf().inVehicle()) return;
            if (reachTarget != null && reachTarget.riding != null) return;
            if (target == null) return;
            if (attack.action() != InteractAction.ATTACK) return;

            double maxReach = AttackReach.capture(player, visibility).maxClosestDistance() + reach_leniency;
            if (target.getMinDistanceForReach() > maxReach
                    && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
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
            int entityId,
            double x,
            double y,
            double z,
            PositionUpdate update,
            boolean holdsShownPosition,
            AtomicBoolean hasAppliedChanges) {
        if (hasAppliedChanges.getAndSet(true)) return;

        FairReachEntity target = targetPlayers.get(entityId);
        if (target == null) return;
        if (holdsShownPosition) {
            Vec3 shown = target.currentPosition;
            target.setTarget(shown.x, shown.y, shown.z);
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
        Vec3 from = fromLerpTarget ? target.targetPosition : target.currentPosition;
        return from.distanceToSqr(x, y, z) > 4096.0;
    }

    /** A replacement client level holds none of the old level's players. */
    public void clearLevel() {
        targetPlayers.clear();
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
        Vec3 currentPosition;
        Vec3 targetPosition;
        int lerpTicks = 0;

        private FairReachEntity(int entityId, Vec3 spawnPos) {
            this.entityId = entityId;
            this.currentPosition = spawnPos;
            this.targetPosition = spawnPos;
        }

        private void setTarget(double x, double y, double z) {
            this.targetPosition = new Vec3(x, y, z);
            this.lerpTicks = 3;
        }

        private void snapTo(double x, double y, double z) {
            this.currentPosition = new Vec3(x, y, z);
            this.targetPosition = currentPosition;
            this.lerpTicks = 0;
        }

        private void tick() {
            if (lerpTicks > 0) {
                double scale = 1d / lerpTicks;
                currentPosition = new Vec3(
                        currentPosition.x + (targetPosition.x - currentPosition.x) * scale,
                        currentPosition.y + (targetPosition.y - currentPosition.y) * scale,
                        currentPosition.z + (targetPosition.z - currentPosition.z) * scale);
                lerpTicks--;
            }
        }

        private double getMinDistanceForReach() {
            PacketEntity trackedEntity = player.compensatedEntities.getEntity(entityId);
            float width = trackedEntity == null ? 0.6f : BoundingBoxSize.getReachWidth(player, trackedEntity);
            float height = trackedEntity == null ? 1.8f : BoundingBoxSize.getReachHeight(player, trackedEntity);
            SimpleCollisionBox thisEntityBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                    currentPosition.x, currentPosition.y, currentPosition.z, width, height);

            return ReachUtils.getMinReachToBox(player, thisEntityBox);
        }
    }
}
