package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.EntityCollisionSelection;
import java.util.List;

public final class UnknownCollisionProvider {
    private UnknownCollisionProvider() {}

    /**
     * Whether EntityGetter#getEntityCollisions gives a Java player outside a vehicle, at {@code playerPos}, a shape for
     * {@code box}, which CollisionGetter#noCollision counts with the blocks: a box of a boat, live shulker or happy
     * ghast it collides with meets {@code box} inflated by 1.0E-7, at its compensated client-tick position, a rider's
     * at its seat.
     */
    public static boolean hasEntityCollision(CultPlayer player, Vec3 playerPos, SimpleCollisionBox box) {
        if (player.isBedrockMovement() || player.compensatedEntities.getSelf().inVehicle()) return false;
        // EntityGetter#getEntityCollisions skips a box whose average side is below 1.0E-7.
        if (((box.maxX - box.minX) + (box.maxY - box.minY) + (box.maxZ - box.minZ)) / 3.0 < 1.0E-7) return false;
        CompensatedEntities entities = player.compensatedEntities;
        List<PacketEntity> candidates = entities.unknownCollisionCandidates();
        if (candidates.isEmpty()) return false;
        PacketEntity selfRidingRoot = entities.selfRidingRoot();
        PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
        SimpleCollisionBox query = box.copy().expand(1.0E-7);
        SimpleCollisionBox envelope = new SimpleCollisionBox();
        SimpleCollisionBox scratch = new SimpleCollisionBox();
        var selection = new EntityCollisionSelection(player, entities.getSelf(), playerPos);
        for (int e = 0, size = candidates.size(); e < size; e++) {
            PacketEntity entity = candidates.get(e);
            if (!selection.mayCollide(entity)) continue;
            boolean rider = entities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot);
            if (EntityPositionWorlds.collider(player, selection, entity, query, rider, envelope, scratch) != null)
                return true;
        }
        return false;
    }
}
