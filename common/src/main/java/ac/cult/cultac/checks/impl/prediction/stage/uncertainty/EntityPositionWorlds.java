package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The entity colliders Entity#canCollideWith admits for a Java move of the player or of a living ridden vehicle: boats,
 * living shulkers, and the happy ghasts HappyGhast#canBeCollidedWith admits. A boat's move uses
 * AbstractBoat#canCollideWith, which also admits pushable entities. Entity#collide collides with each entity at the one
 * position the client holds it, as its bounding box (EntityGetter#getEntityCollisions, EntityGetter.java:54-69), so
 * each world holds one possible box of each such entity that may lie in the move's region, and no box for an entity
 * the client may hold outside it.
 */
public final class EntityPositionWorlds {
    public static final int MAX_POSITION_SETS = 16;

    private EntityPositionWorlds() {}

    /**
     * The colliders of each world for a move within {@code region}: one empty world when no entity may lie in it, or
     * null where the worlds do not decide the move.
     */
    public static @Nullable List<List<SimpleCollisionBox>> of(
            CultPlayer player, SimulationContext context, Vec3 playerPos, SimpleCollisionBox region) {
        PacketEntity vehicle = context.getVehicle();
        if (player.isBedrockMovement() || vehicle != null && vehicle.isBoat()) return null;
        List<SimpleCollisionBox> dynamicBlocks = new ArrayList<>();
        UnknownCollisionProvider.addDynamicBlockUnknownCollisions(player, context, region, dynamicBlocks);
        if (!dynamicBlocks.isEmpty()) return null;

        List<List<SimpleCollisionBox>> choices = choices(player, context, playerPos, region);
        int worlds = 1;
        for (int e = 0; e < choices.size(); e++) {
            worlds *= choices.get(e).size();
            if (worlds > MAX_POSITION_SETS) return null;
        }

        List<List<SimpleCollisionBox>> result = new ArrayList<>(worlds);
        for (int w = 0; w < worlds; w++) {
            List<SimpleCollisionBox> world = new ArrayList<>(choices.size());
            int digits = w;
            for (int e = 0; e < choices.size(); e++) {
                List<SimpleCollisionBox> boxes = choices.get(e);
                SimpleCollisionBox box = boxes.get(digits % boxes.size());
                digits /= boxes.size();
                if (box != null) world.add(box);
            }
            result.add(world);
        }
        return result;
    }

    /**
     * Every box an entity of the worlds {@link #of} builds may hold within {@code region}, however many worlds there
     * are, for a Java move.
     */
    public static List<SimpleCollisionBox> possibleBoxes(
            CultPlayer player, SimulationContext context, Vec3 playerPos, SimpleCollisionBox region) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        for (List<SimpleCollisionBox> choice : choices(player, context, playerPos, region)) {
            for (int i = 0; i < choice.size(); i++) {
                if (choice.get(i) != null) boxes.add(choice.get(i));
            }
        }
        return boxes;
    }

    /** For each entity that may lie in {@code region}, its distinct boxes there, and null when it may lie elsewhere. */
    private static List<List<SimpleCollisionBox>> choices(
            CultPlayer player, SimulationContext context, Vec3 playerPos, SimpleCollisionBox region) {
        PacketEntity vehicle = context.getVehicle();
        CompensatedEntities entities = player.compensatedEntities;
        PacketEntity selfRidingRoot = entities.selfRidingRoot();
        PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
        PacketEntity rootOfSelf = vehicle != null ? entities.rootOfSelf() : null;
        SimpleCollisionBox envelope = new SimpleCollisionBox();
        SimpleCollisionBox scratch = new SimpleCollisionBox();
        List<List<SimpleCollisionBox>> choices = new ArrayList<>();
        List<PacketEntity> candidates = entities.unknownCollisionCandidates();
        for (int e = 0, size = candidates.size(); e < size; e++) {
            PacketEntity entity = candidates.get(e);
            if (entities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot)) continue;
            if (vehicle != null && entities.sharesRootVehicleWithSelf(entity, rootOfSelf)) continue;
            if (!UnknownCollisionProvider.collidesAtPossiblePositions(
                    entities.getSelf(), vehicle, playerPos, entity, envelope, scratch)) {
                continue;
            }
            if (!boundingBox(entity, entity.getPossibleMovementCollisionBoxes(envelope, scratch))
                    .isIntersected(region)) {
                continue;
            }
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            boolean outside = false;
            for (int i = 0, count = entity.possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
                SimpleCollisionBox box = boundingBox(entity, entity.possibleMovementCollisionBoxCandidate(i, scratch));
                if (!box.isIntersected(region)) {
                    outside = true;
                } else if (!containsBox(boxes, box)) {
                    boxes.add(box.copy());
                }
            }
            if (boxes.isEmpty()) continue;
            if (outside) boxes.add(null);
            choices.add(boxes);
        }
        return choices;
    }

    /**
     * Raises {@code region}, the move Entity#collide sweeps, to the region whose entities it collides with: up to
     * maxUpStep above the move (26.3, Entity.java:1323), or along the move only for a Java client before 26.3 (26.2,
     * Entity.java:1276). The step collides with the same entities.
     */
    public static SimpleCollisionBox withStepEntities(CultPlayer player, SimpleCollisionBox region) {
        if (player.isBedrockMovement() || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            region.maxY += Math.max(0.0D, player.getMaxUpStep());
        }
        return region;
    }

    /** The bounding box of {@code entity} at {@code box}, one of its position boxes. */
    private static SimpleCollisionBox boundingBox(PacketEntity entity, SimpleCollisionBox box) {
        return entity instanceof PacketEntityShulker shulker ? shulker.boundingBox(box) : box;
    }

    /** Whether {@code worlds} is the one empty world. */
    public static boolean noEntities(List<List<SimpleCollisionBox>> worlds) {
        return worlds.size() == 1 && worlds.get(0).isEmpty();
    }

    private static boolean containsBox(List<SimpleCollisionBox> boxes, SimpleCollisionBox box) {
        for (int i = 0; i < boxes.size(); i++) {
            SimpleCollisionBox other = boxes.get(i);
            if (other.minX == box.minX
                    && other.minY == box.minY
                    && other.minZ == box.minZ
                    && other.maxX == box.maxX
                    && other.maxY == box.maxY
                    && other.maxZ == box.maxZ) {
                return true;
            }
        }
        return false;
    }
}
