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
     * {@code transaction}: strongKnockback adds to the delta movement of the local player within the ravager's box
     * inflated by 4.0, from where both are between the client's ticks.
     */
    public void onRavagerRoar(int ravagerId, int transaction) {
        long sequence = player.nextImpulseSequence();
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            Vec3 push = roarPush(ravagerId);
            if (push == null) return;
            TransactionVel velocity = new TransactionVel(push, transaction, false, isSetbackVal, ravagerId, sequence);
            lastSent = velocity;
            confirmVelocity(velocity, push);
        });
    }

    // Ravager#applyRoarKnockbackClient selects only living, locally authoritative entities; a passenger's delta
    // movement is reset by Entity#rideTick.
    private @Nullable Vec3 roarPush(int ravagerId) {
        PacketEntitySelf self = player.compensatedEntities.getSelf();
        if (!self.actionAlive || self.inVehicle()) return null;
        PacketEntity ravager = player.compensatedEntities.getEntity(ravagerId);
        if (ravager == null
                || ravager.type != EntityTypeIds.RAVAGER
                || ravager.clientPhysicalPosition == null
                || !ravager.clientPhysicalPositionExact) return null;
        Vec3 at = ravager.clientPhysicalPosition;
        SimpleCollisionBox reach = GetBoundingBox.getPacketEntityBoundingBox(player, at.x, at.y, at.z, ravager)
                .expand(4.0);
        SimpleCollisionBox box = player.boundingBox;
        if (!reach.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) return null;
        double xd = player.x - at.x;
        double zd = player.z - at.z;
        double dd = Math.max(xd * xd + zd * zd, 0.001);
        return new Vec3(xd / dd * 4.0, 0.2, zd / dd * 4.0);
    }
}
