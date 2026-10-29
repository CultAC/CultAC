package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundExplode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRavager;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedVehicleState;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

@BedrockSupported
public class ExplosionHandler extends PacketModHandler {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("AntiExplosion")
            .stableKey("cult.velocity.anti_explosion")
            .configName("Explosion")
            .description("Did not take the expected explosion knockback")
            .setback(10)
            .build();
    // The ravagers whose stun the client has handled, until their countdowns end.
    private final transient List<PacketEntityRavager> countingDown = new ArrayList<>();
    // The ravagers whose aiStep roared in the client tick being ended, until its travel is known.
    private final transient List<PacketEntityRavager> roaredThisTick = new ArrayList<>();

    public ExplosionHandler(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
    }

    @Override
    protected String getAdvantageConfigPath() {
        return "cult.checks.explosion";
    }

    @CultPacketHandler
    public void onExplode(PacketSendEvent<ClientboundExplode> event, CultPlayer player, ClientboundExplode packet) {
        // The client handles a sent dismount before this packet, so the knockback pushes the player.
        if (player.compensatedEntities.vehicles.serverPlayerVehicle != null
                && !player.compensatedEntities.vehicles.hasPendingServerDismount()) return;

        Vec3 velocity = new Vec3(
                packet.knockback().x(),
                packet.knockback().y(),
                packet.knockback().z());

        if (velocity.x != 0 || velocity.y != 0 || velocity.z != 0) {
            if (shouldUseBundledProof()) {
                CultPlayer.TrackedTransaction transaction = bundlePacketWithTrailingTransaction(event, packet);
                if (transaction != null) {
                    handleEventAfterTransaction(
                            velocity, false, transaction.transaction(), TransactionVel.UNKNOWN_SOURCE_ENTITY_ID);
                    return;
                }
            }

            player.sendTransaction();
            handleEvent(velocity, false, event, TransactionVel.UNKNOWN_SOURCE_ENTITY_ID);
        }
    }

    /**
     * Ravager#handleEntityEvent(69) runs applyRoarKnockbackClient once the client handles the event at
     * {@code transaction}, from where the ravager and the local player are between the client's ticks.
     */
    public void onRavagerRoar(int ravagerId, int transaction) {
        long sequence = player.nextImpulseSequence();
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            PacketEntity ravager = player.compensatedEntities.getEntity(ravagerId);
            if (ravager != null && ravager.type == EntityTypeIds.RAVAGER) {
                roarFromClientPosition(ravager, player.x, player.z, player.boundingBox, transaction, sequence, false);
            }
        });
    }

    /** Ravager#handleEntityEvent(39) sets stunnedTick once the client handles the event at {@code transaction}. */
    public void onRavagerStun(int ravagerId, int transaction) {
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            if (!(player.compensatedEntities.getEntity(ravagerId) instanceof PacketEntityRavager ravager)) return;
            ravager.stun();
            if (!countingDown.contains(ravager)) countingDown.add(ravager);
        });
    }

    /**
     * ClientLevel#tickEntities runs the aiStep of a ravager that ticks before the local player's riding tree first, so
     * its roar pushes this tick's travel from {@code from}, where the player, or the vehicle it controls, starts it.
     */
    public void beforeLocalTravel(Vec3 from) {
        if (countingDown.isEmpty()) return;
        CompensatedEntities entities = player.compensatedEntities;
        long selfOrder = entities.clientTickOrder(entities.getSelf());
        for (PacketEntityRavager ravager : countingDown) {
            // Self ticks after an entity sharing its order.
            if (!ravager.roarPendingThisTick(player) || entities.clientTickOrder(ravager) > selfOrder) continue;
            ravager.markRoaredBeforeLocalTravel();
            Vec3 at = ravager.exactPositionAfterClientTick(player);
            if (at == null) continue;
            roar(
                    ravager,
                    at,
                    from.x,
                    from.z,
                    GetBoundingBox.getCollisionBoxForPlayer(player, from.x, from.y, from.z),
                    player.lastTransactionReceived.get(),
                    player.nextImpulseSequence(),
                    false);
        }
    }

    /** Ravager#aiStep's roar that no travel of this client tick has taken yet, applied once its travel is known. */
    public void onCountdownRoar(PacketEntityRavager ravager) {
        roaredThisTick.add(ravager);
    }

    /**
     * A tick that sends no position is predicted at its tick end, after the ravagers ticked, so the roar of one that
     * ticks before the local player pushes that travel from {@code from}.
     */
    public void beforeNoPositionTravel(Vec3 from) {
        if (roaredThisTick.isEmpty()) return;
        CompensatedEntities entities = player.compensatedEntities;
        long selfOrder = entities.clientTickOrder(entities.getSelf());
        roaredThisTick.removeIf(ravager -> {
            if (entities.clientTickOrder(ravager) > selfOrder) return false;
            roarFromClientPosition(
                    ravager,
                    from.x,
                    from.z,
                    GetBoundingBox.getCollisionBoxForPlayer(player, from.x, from.y, from.z),
                    player.lastTransactionReceived.get(),
                    player.nextImpulseSequence(),
                    false);
            return true;
        });
    }

    /** The roars of ravagers that tick after the local player's riding tree, from where both are after its travel. */
    public void afterLocalTravel() {
        if (roaredThisTick.isEmpty()) return;
        CompensatedEntities entities = player.compensatedEntities;
        long selfOrder = entities.clientTickOrder(entities.getSelf());
        for (PacketEntityRavager ravager : roaredThisTick) {
            if (entities.clientTickOrder(ravager) <= selfOrder) continue;
            roarFromClientPosition(
                    ravager,
                    player.x,
                    player.z,
                    player.boundingBox,
                    player.lastTransactionReceived.get(),
                    player.nextImpulseSequence(),
                    true);
        }
        roaredThisTick.clear();
    }

    public void onCountdownEnd(PacketEntityRavager ravager) {
        countingDown.remove(ravager);
    }

    // From where the ravager is between the client's ticks.
    private void roarFromClientPosition(
            PacketEntity ravager,
            double x,
            double z,
            SimpleCollisionBox box,
            int transaction,
            long sequence,
            boolean afterTravel) {
        if (ravager.clientPhysicalPosition == null || !ravager.clientPhysicalPositionExact) return;
        roar(ravager, ravager.clientPhysicalPosition, x, z, box, transaction, sequence, afterTravel);
    }

    /**
     * The client's Ravager#roar from {@code at}: strongKnockback adds to the delta movement of the roar's target at
     * ({@code x}, {@code z}) when {@code box}, its bounding box, is within the ravager's box inflated by 4.0.
     * {@code afterTravel} is a roar in the client tick being ended, after its travel, which the next tick consumes.
     */
    private void roar(
            PacketEntity ravager,
            Vec3 at,
            double x,
            double z,
            SimpleCollisionBox box,
            int transaction,
            long sequence,
            boolean afterTravel) {
        PacketEntity target = roarTarget();
        if (target == null) return;
        SimpleCollisionBox reach = GetBoundingBox.getPacketEntityBoundingBox(player, at.x, at.y, at.z, ravager)
                .expand(4.0);
        if (!reach.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) return;
        double xd = x - at.x;
        double zd = z - at.z;
        double dd = Math.max(xd * xd + zd * zd, 0.001);
        Vec3 push = new Vec3(xd / dd * 4.0, 0.2, zd / dd * 4.0);
        if (target != player.compensatedEntities.getSelf()) {
            player.compensatedEntities.vehicles.applyVehiclePush(target, push);
            return;
        }
        TransactionVel velocity =
                new TransactionVel(push, transaction, false, isSetbackVal, ravager.getEntityId(), sequence);
        // The tick end being handled counts this tick, so the next one counts the tick that consumes the push.
        if (afterTravel) velocity.setConsumedAtClientTick(player.packetStateData.acceptedClientTick + 2);
        lastSent = velocity;
        confirmVelocity(velocity, push);
    }

    // Ravager#ROAR_TARGET_ON_CLIENT selects living, locally authoritative entities other than ravagers. A passenger's
    // delta movement is reset by Entity#rideTick, so a riding player is pushed only through the vehicle it controls,
    // whose position and box are then the player's.
    private @Nullable PacketEntity roarTarget() {
        PacketEntitySelf self = player.compensatedEntities.getSelf();
        if (!self.actionAlive) return null;
        PacketEntity vehicle = self.getRiding();
        if (vehicle == null) return self;
        CompensatedVehicleState vehicles = player.compensatedEntities.vehicles;
        return vehicle.isLivingEntity()
                        && vehicle.actionAlive
                        && vehicle.type != EntityTypeIds.RAVAGER
                        && vehicle == vehicles.getVelocityMovementVehicle()
                        && vehicles.canClientAuthoritativelyMoveVisibleRoot(vehicle)
                ? vehicle
                : null;
    }
}
