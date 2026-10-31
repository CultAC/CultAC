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
import org.jetbrains.annotations.Nullable;

/** EntityGetter#getEntityCollisions at the mover's place in ClientLevel#tickEntities. */
public final class EntityPositionWorlds {
    private EntityPositionWorlds() {}

    /**
     * All possible colliders for presence queries and broad-phase discovery. Movement resolution must use
     * {@link #forActor}: alternative positions of one entity cannot clip the same move together.
     */
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
            if (!selection.mayCollide(entity)) continue;
            List<SimpleCollisionBox> choices = choices(
                    player,
                    selection,
                    entity,
                    query,
                    entities.isUntrackedRider(entity, ownRoot, serverRoot),
                    bounds,
                    scratch);
            for (int i = 0; i < choices.size(); i++) {
                SimpleCollisionBox box = choices.get(i);
                if (box != null) boxes.add(box);
            }
        }
        return boxes;
    }

    /**
     * The box EntityGetter#getEntityCollisions gives the actor of {@code selection} for {@code entity}, which
     * {@link EntityCollisionSelection#mayCollide} admits, when it meets {@code query}, the search area already inflated
     * by 1.0E-7; else null. A rider outside the local player's riding trees collides at the place its vehicle chain
     * gives it, ruled out without allocating when no seat can reach the query. {@code bounds} and {@code scratch} are
     * working space.
     */
    public static @Nullable SimpleCollisionBox collider(
            CultPlayer player,
            EntityCollisionSelection selection,
            PacketEntity entity,
            SimpleCollisionBox query,
            boolean untrackedRider,
            SimpleCollisionBox bounds,
            SimpleCollisionBox scratch) {
        List<SimpleCollisionBox> boxes = choices(player, selection, entity, query, untrackedRider, bounds, scratch);
        return boxes.isEmpty() ? null : boxes.getFirst();
    }

    /** The distinct collision choices for one entity, including no collider when a valid position is outside. */
    private static List<SimpleCollisionBox> choices(
            CultPlayer player,
            EntityCollisionSelection selection,
            PacketEntity entity,
            SimpleCollisionBox query,
            boolean untrackedRider,
            SimpleCollisionBox bounds,
            SimpleCollisionBox scratch) {
        if (untrackedRider) {
            if (!player.compensatedEntities.untrackedRiderMayReach(entity, query, bounds, scratch)) return List.of();
        } else {
            // Bounds are broad-phase only. They never clip a move or substitute for a client-tick shape.
            SimpleCollisionBox broad =
                    entity.toBoundingBox(player, entity.getPossibleMovementCollisionBoxes(bounds, scratch));
            if (!intersects(query, broad)) return List.of();
        }
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        boolean outside = false;
        for (int i = 0, count = entity.possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
            SimpleCollisionBox box =
                    selection.collisionBox(entity, entity.possibleMovementCollisionBoxCandidate(i, scratch));
            if (!intersects(query, box) || !selection.collidesAt(entity, box)) outside = true;
            else if (!containsBox(boxes, box)) boxes.add(box);
        }
        if (outside && !boxes.isEmpty()) boxes.add(null);
        return boxes;
    }

    private static boolean intersects(SimpleCollisionBox query, SimpleCollisionBox box) {
        return query.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static boolean containsBox(List<SimpleCollisionBox> boxes, SimpleCollisionBox box) {
        for (SimpleCollisionBox other : boxes) {
            if (other.minX == box.minX && other.minY == box.minY && other.minZ == box.minZ
                    && other.maxX == box.maxX && other.maxY == box.maxY && other.maxZ == box.maxZ) return true;
        }
        return false;
    }

    /** One box per entity per world, as EntityGetter#getEntityCollisions supplies to Entity#collide. */
    public static List<List<SimpleCollisionBox>> forActor(
            CultPlayer player, PacketEntity actor, Vec3 playerPos, SimpleCollisionBox region) {
        var entities = player.compensatedEntities;
        var selection = new EntityCollisionSelection(player, actor, playerPos);
        Collection<PacketEntity> candidates =
                selection.includesPushables() ? entities.entityMap.values() : entities.unknownCollisionCandidates();
        List<List<SimpleCollisionBox>> worlds = new ArrayList<>();
        worlds.add(new ArrayList<>());
        if (((region.maxX - region.minX) + (region.maxY - region.minY) + (region.maxZ - region.minZ)) / 3.0 < 1.0E-7)
            return worlds;
        SimpleCollisionBox query = region.copy().expand(1.0E-7);
        SimpleCollisionBox bounds = new SimpleCollisionBox();
        SimpleCollisionBox scratch = new SimpleCollisionBox();
        PacketEntity ownRoot = entities.selfRidingRoot();
        PacketEntity serverRoot = entities.serverVehicleRoot();
        for (PacketEntity entity : candidates) {
            if (!selection.mayCollide(entity)) continue;
            List<SimpleCollisionBox> choices = choices(
                    player, selection, entity, query, entities.isUntrackedRider(entity, ownRoot, serverRoot),
                    bounds, scratch);
            if (choices.isEmpty()) continue;
            int count = worlds.size();
            // Clone the preceding worlds before adding this entity, retaining the no-collider choice too.
            for (int i = 1; i < choices.size(); i++) {
                for (int w = 0; w < count; w++) worlds.add(new ArrayList<>(worlds.get(w)));
            }
            for (int i = 0; i < choices.size(); i++) {
                SimpleCollisionBox box = choices.get(i);
                if (box == null) continue;
                for (int w = 0; w < count; w++) worlds.get(i * count + w).add(box);
            }
        }
        return worlds;
    }

    public static List<List<SimpleCollisionBox>> of(
            CultPlayer player, SimulationContext context, Vec3 playerPos, SimpleCollisionBox region) {
        if (player.isBedrockMovement()) return null;
        PacketEntity actor = context.getVehicle() == null ? player.compensatedEntities.getSelf() : context.getVehicle();
        return forActor(player, actor, playerPos, region);
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
