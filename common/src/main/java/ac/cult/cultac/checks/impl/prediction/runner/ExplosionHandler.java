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
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.latency.CompensatedVehicleState;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.jetbrains.annotations.Nullable;

@BedrockSupported
public class ExplosionHandler extends PacketModHandler {

    public ExplosionHandler(CultPlayer cultPlayer) {
        super(
                cultPlayer,
                CheckInfo.builder()
                        .name("AntiExplosion")
                        .stableKey("cult.velocity.anti_explosion")
                        .configName("Explosion")
                        .description("Did not take the expected explosion knockback")
                        .setback(10)
                        .build());
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
            if (ravager == null
                    || ravager.type != EntityTypeIds.RAVAGER
                    || ravager.clientPhysicalPosition == null
                    || !ravager.clientPhysicalPositionExact) return;
            roar(
                    ravager,
                    ravager.clientPhysicalPosition,
                    player.x,
                    player.z,
                    player.boundingBox,
                    transaction,
                    sequence);
        });
    }

    /**
     * The client's Ravager#roar from {@code at}: strongKnockback adds to the delta movement of the roar's target at
     * ({@code x}, {@code z}) when {@code box}, its bounding box, is within the ravager's box inflated by 4.0.
     */
    private void roar(
            PacketEntity ravager, Vec3 at, double x, double z, SimpleCollisionBox box, int transaction, long sequence) {
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
