package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.EntityCollisionSelection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** EntityGetter#getEntityCollisions at the mover's place in ClientLevel#tickEntities. */
public final class EntityPositionWorlds {
    private EntityPositionWorlds() {}

    /** One collision set from compensated client state; reach bounds and animation envelopes are never colliders. */
    public static List<SimpleCollisionBox> colliders(
            CultPlayer player, PacketEntity actor, Vec3 playerPos, SimpleCollisionBox region) {
        var entities = player.compensatedEntities;
        var selection = new EntityCollisionSelection(player, actor, playerPos);
        Collection<PacketEntity> candidates =
                selection.includesPushables() ? entities.entityMap.values() : entities.unknownCollisionCandidates();
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        if (((region.maxX - region.minX) + (region.maxY - region.minY) + (region.maxZ - region.minZ)) / 3.0 < 1.0E-7)
            return boxes;
        // EntityGetter#getEntityCollisions inflates once, then AABB#intersects uses strict comparisons without
        // subtracting a second epsilon. SimpleCollisionBox#isIntersected would cancel this inflation at a face.
        SimpleCollisionBox query = region.copy().expand(1.0E-7);
        SimpleCollisionBox bounds = new SimpleCollisionBox();
        SimpleCollisionBox scratch = new SimpleCollisionBox();
        PacketEntity ownRoot = entities.selfRidingRoot();
        PacketEntity serverRoot = entities.serverVehicleRoot();
        for (PacketEntity entity : candidates) {
            if (entities.isUntrackedRider(entity, ownRoot, serverRoot) || !selection.mayCollide(entity)) continue;
            // Bounds are broad-phase only. They never clip a move or substitute for the client-tick shape below.
            SimpleCollisionBox broad =
                    selection.collisionBox(entity, entity.getPossibleMovementCollisionBoxes(bounds, scratch));
            if (!query.intersects(broad.minX, broad.minY, broad.minZ, broad.maxX, broad.maxY, broad.maxZ)) continue;
            SimpleCollisionBox box = selection.collisionBox(entity, entity.getClientMovementCollisionBox());
            if (query.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
                    && selection.collidesAt(entity, box)) boxes.add(box);
        }
        return boxes;
    }

    /** Compatibility for readers of collision sets: there is exactly one compensated set. */
    public static List<List<SimpleCollisionBox>> of(
            CultPlayer player, SimulationContext context, Vec3 playerPos, SimpleCollisionBox region) {
        if (player.isBedrockMovement()) return null;
        PacketEntity actor = context.getVehicle() == null ? player.compensatedEntities.getSelf() : context.getVehicle();
        return List.of(colliders(player, actor, playerPos, region));
    }

    /** 26.3 Entity#collide gathers entities up to maxUpStep above the movement; earlier Java versions do not. */
    public static SimpleCollisionBox withStepEntities(CultPlayer player, SimpleCollisionBox region) {
        if (player.isBedrockMovement() || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3))
            region.maxY += Math.max(0.0D, player.getMaxUpStep());
        return region;
    }

    public static boolean noEntities(List<List<SimpleCollisionBox>> worlds) {
        return worlds.size() == 1 && worlds.getFirst().isEmpty();
    }
}
