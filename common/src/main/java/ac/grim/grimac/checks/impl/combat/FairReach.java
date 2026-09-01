package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.BoundingBoxSize;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.phys.Vec3;
import io.netty.util.concurrent.ScheduledFuture;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.AllArgsConstructor;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    double max_reach = 3.1;


    ScheduledFuture<?> tickStartTask = null;
    ScheduledFuture<?> tickEndTask = null;


    public FairReach(GrimPlayer player) {
        super(player, CheckInfo.builder().name("FairReach").stableKey("grim.combat.fair_reach").build());
    }

    @GrimPacketHandler
    public void onAddEntity(PacketSendEvent event, GrimPlayer player, ClientboundAddEntityPacket packet) {
        if (packet.getType() == EntityTypesCompat.PLAYER) {
            addEntity(packet.getId(), new net.minecraft.world.phys.Vec3(packet.getX(), packet.getY(), packet.getZ()));
        }
    }

    @GrimPacketHandler
    public void onRemoveEntities(PacketSendEvent event, GrimPlayer player, ClientboundRemoveEntitiesPacket packet) {
        // Server won't process destroyed entity hits anyway... might as well remove them immediately
        for (int entityId : packet.getEntityIds().toIntArray()) {
            targetPlayers.remove(entityId);
        }
    }

    public void handleEntityMove(int entityId, double x, double y, double z) {
        AtomicBoolean hasAppliedChanges = new AtomicBoolean(false);

        // Ensure the client has received this before MAX_PING_TIME, or apply them sooner if client responds in time
        player.latencyUtils.addRealTimeTaskNext(() -> applyChanges(entityId, x, y, z, hasAppliedChanges));
        player.nettyScheduler.runTaskInMs(() -> applyChanges(entityId, x, y, z, hasAppliedChanges), max_ping_compensation);
    }

    private void addEntity(int entityId, net.minecraft.world.phys.Vec3 spawnPosition) {
        targetPlayers.put(entityId, new FairReachEntity(entityId, new Vec3(spawnPosition.x, spawnPosition.y, spawnPosition.z)));
    }

    private void handleInteract(final PacketReceiveEvent event, NmsPacketUtil.InteractData attack) {
        // The server entity position will temporarily desync from the player
        // If it exceeds the defined limit, 3.1, from the optimal reach angle, we will cancel the hit
        if (!player.isDisabled() && attack != null) {

            FairReachEntity target = targetPlayers.get(attack.entityId());
            PacketEntity reachTarget = player.compensatedEntities.getEntity(attack.entityId());

            if (player.compensatedEntities.getSelf().inVehicle()) return;
            if (reachTarget != null && reachTarget.riding != null) return;
            if (target == null) return;
            if (attack.action() != NmsPacketUtil.InteractAction.ATTACK) return;

            if (target.getMinDistanceForReach() > max_reach) {
                player.onPacketCancel();
                event.setCancelled(true);
            }
        }

    }

    @GrimPacketHandler
    public void onInteract(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        handleInteract(event, NmsPacketUtil.readInteract(packet));
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        handleInteract(event, NmsPacketUtil.readAttack(packet));
    }

    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
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
        if (playerTickingStatus == TickingStatus.BEFORE_VALID_TICK || playerTickingStatus == TickingStatus.TICKED_BEFORE_MINIMUM) {
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

        this.tickStartTask = player.nettyScheduler.runTaskInMs(() -> {
            // If the player ticked too soon... tick, and restart this process
            // Otherwise, prepare for a valid tick
            if (TickingStatus.TICKED_BEFORE_MINIMUM == playerTickingStatus) {
                playerTickingStatus = TickingStatus.ALLOWED_TO_TICK;
                tick();
            } else {
                playerTickingStatus = TickingStatus.ALLOWED_TO_TICK;
            }
        }, 50 - max_position_packet_jitter);

        this.tickEndTask = player.nettyScheduler.runTaskInMs(() -> {
            // The player missed their chance at a valid tick, tick anyway for them
            if (TickingStatus.ALLOWED_TO_TICK == playerTickingStatus) {
                tick();
            }
        }, 50 + max_position_packet_jitter);
    }

    private void applyChanges(int entityId, double x, double y, double z, AtomicBoolean hasAppliedChanges) {
        if (hasAppliedChanges.getAndSet(true)) return;

        FairReachEntity target = targetPlayers.get(entityId);
        if (target == null) return;
        target.setTarget(x, y, z);
    }

    public void onPlayerQuit() {
        if (this.tickStartTask != null) this.tickStartTask.cancel(true);
        if (this.tickEndTask != null) this.tickEndTask.cancel(true);
    }

    @Override
    public void reload() {
        super.reload();
        max_position_packet_jitter = getConfig().getIntElse("FairReach.max_position_packet_jitter", 15);
        max_ping_compensation = getConfig().getIntElse("FairReach.max_ping_compensation", 300);
        max_reach = getConfig().getDoubleElse("FairReach.max_reach", 3.1);
    }

    enum TickingStatus {
        TICKED_BEFORE_MINIMUM,
        ALLOWED_TO_TICK,
        BEFORE_VALID_TICK
    }

    @AllArgsConstructor
    static
    class PendingEntityMove {
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

        private void tick() {
            if (lerpTicks > 0) {
                currentPosition = currentPosition.add(targetPosition.subtract(currentPosition).scale(1d / lerpTicks));
                lerpTicks--;
            }
        }

        private double getMinDistanceForReach() {
            PacketEntity trackedEntity = player.compensatedEntities.getEntity(entityId);
            float width = trackedEntity == null ? 0.6f : BoundingBoxSize.getReachWidth(player, trackedEntity);
            float height = trackedEntity == null ? 1.8f : BoundingBoxSize.getReachHeight(player, trackedEntity);
            SimpleCollisionBox thisEntityBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                    currentPosition.x, currentPosition.y, currentPosition.z,
                    width, height);

            return ReachUtils.getMinReachToBox(player, thisEntityBox);
        }
    }
}
