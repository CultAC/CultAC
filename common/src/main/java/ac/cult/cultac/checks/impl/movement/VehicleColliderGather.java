package ac.cult.cultac.checks.impl.movement;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether Entity#collide gathers any collider for a locally controlled vehicle's move. Entity#collectCollidersIgnoringWorldBorder
 * adds the entity colliders of Level#getEntityCollisions, the world border's shape whenever
 * WorldBorder#isInsideCloseToBorder holds for {@code box.expandTowards(movement)}, and every block shape that
 * intersects that box; Shapes#collide returns 0 for an axis distance under 1.0E-7 whenever that list is not empty.
 */
final class VehicleColliderGather {
    private VehicleColliderGather() {}

    /**
     * TRUE when Entity#collide gathers a collider for every pre-collision movement in {@code movements}, FALSE when it
     * gathers none for any of them, UNKNOWN when a collider the client may hold or an entity decides it.
     */
    static DesyncStatus gathers(CultPlayer player, PredictionResult result, SimpleCollisionBox movements) {
        Vec3 start = result.getSimulationContext().getStart();
        SimpleCollisionBox box = result.getSimulationContext().getFromActualPose();
        DesyncStatus border = player.checkManager
                .getWorldBorder()
                .isInsideCloseToBorder(start.x, start.z, bbMax(box, 0.0D, 0.0D, 0.0D, 0.0D), bbMax(box, movements));
        if (border == DesyncStatus.TRUE) return DesyncStatus.TRUE;

        SimpleCollisionBox reach = box.copy()
                .expandToCoordinate(movements.minX, movements.minY, movements.minZ)
                .expandToCoordinate(movements.maxX, movements.maxY, movements.maxZ);
        boolean otherBlocks = mayHoldOtherBlocks(player, reach);
        // Every movement's expanded box contains the box expanded by the distance all of them share on each axis.
        double sharedX = shared(movements.minX, movements.maxX);
        double sharedY = shared(movements.minY, movements.maxY);
        double sharedZ = shared(movements.minZ, movements.maxZ);
        SimpleCollisionBox certain = box.copy().expandToCoordinate(sharedX, sharedY, sharedZ);
        if (!otherBlocks && hasBlockCollider(player, certain, start.y)) return DesyncStatus.TRUE;
        if (border == DesyncStatus.FALSE
                && !otherBlocks
                && !hasBlockCollider(player, reach, start.y)
                && !mayGatherEntity(player, result, reach)) {
            return DesyncStatus.FALSE;
        }
        return DesyncStatus.UNKNOWN;
    }

    /**
     * The largest WorldBorder#isInsideCloseToBorder bbMax of {@code box.expandTowards(x, 0, z)} for x and z at these
     * ends; AABB#getXsize and getZsize grow with the distance on either side.
     */
    private static double bbMax(SimpleCollisionBox box, double minX, double maxX, double minZ, double maxZ) {
        double xSize = Math.max(sizeTowards(box.minX, box.maxX, minX), sizeTowards(box.minX, box.maxX, maxX));
        double zSize = Math.max(sizeTowards(box.minZ, box.maxZ, minZ), sizeTowards(box.minZ, box.maxZ, maxZ));
        return Math.max(Math.max(xSize, zSize), 1.0D);
    }

    /** The largest bbMax of any movement in {@code movements}. */
    private static double bbMax(SimpleCollisionBox box, SimpleCollisionBox movements) {
        return bbMax(box, movements.minX, movements.maxX, movements.minZ, movements.maxZ);
    }

    /** The distance every movement from {@code min} to {@code max} on one axis moves at least, signed; 0 if none. */
    private static double shared(double min, double max) {
        return min > 0.0D ? min : max < 0.0D ? max : 0.0D;
    }

    /** AABB#expandTowards then getXsize, or getZsize, along one horizontal axis. */
    private static double sizeTowards(double min, double max, double distance) {
        return distance < 0.0D ? max - (min + distance) : (max + distance) - min;
    }

    /** Whether a block shape the engine knows strictly intersects {@code query}, as BlockCollisions gathers it. */
    private static boolean hasBlockCollider(CultPlayer player, SimpleCollisionBox query, double entityBottom) {
        List<SimpleCollisionBox> colliders = new ArrayList<>();
        Collisions.getCollisionBoxes(player, query, colliders, false, entityBottom);
        for (int i = 0, size = colliders.size(); i < size; i++) {
            // The border planes span every height; block shapes never do.
            if (colliders.get(i).minY != Double.NEGATIVE_INFINITY) return true;
        }
        return false;
    }

    /** Whether the client may hold a block in {@code query} other than the one the engine holds. */
    private static boolean mayHoldOtherBlocks(CultPlayer player, SimpleCollisionBox query) {
        CompensatedWorld world = player.compensatedWorld;
        if (world.hasRecentClientCollisionChanges(query)) return true;
        List<SimpleCollisionBox> dynamic = new ArrayList<>();
        world.addDynamicBlockCollisionUncertaintyBoxes(query, dynamic);
        if (!dynamic.isEmpty()) return true;
        int minX = (int) Math.floor(query.minX) - 1;
        int minY = (int) Math.floor(query.minY) - 1;
        int minZ = (int) Math.floor(query.minZ) - 1;
        int maxX = (int) Math.floor(query.maxX) + 1;
        int maxY = (int) Math.floor(query.maxY) + 1;
        int maxZ = (int) Math.floor(query.maxZ) + 1;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return true;
            }
        }
        if (!world.hasPendingBlockPredictions()) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.hasPendingBlockPrediction(new BlockPos(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a bubble column lies within a block of the vehicle's box: AbstractBoat#tickBubbleColumn and
     * BubbleColumnBlock#entityInside change the vertical deltaMovement after Entity#move.
     */
    static boolean nearBubbleColumn(CultPlayer player, PredictionResult result) {
        SimpleCollisionBox box =
                result.getSimulationContext().getFromActualPose().expand(1.0D);
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                    if (BlockIds.is(player.compensatedWorld.getBlockStateIdAt(x, y, z), BlockIds.BUBBLE_COLUMN)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether an entity's possible box comes near {@code reach} raised by the vehicle's step height, the box
     * Level#getEntityCollisions searches; the vehicle's own passengers are never gathered.
     */
    private static boolean mayGatherEntity(CultPlayer player, PredictionResult result, SimpleCollisionBox reach) {
        CompensatedEntities entities = player.compensatedEntities;
        SimpleCollisionBox query = reach.copy()
                .expandToCoordinate(0.0D, result.getInitialStartingVel().maxUpStep(player), 0.0D);
        PacketEntity rootOfSelf = entities.rootOfSelf();
        SimpleCollisionBox entityBox = new SimpleCollisionBox();
        SimpleCollisionBox scratch = new SimpleCollisionBox();
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entities.isUntrackedRider(entity)) continue;
            if (entities.sharesRootVehicleWithSelf(entity, rootOfSelf)) continue;
            SimpleCollisionBox possible =
                    entity.toBoundingBox(player, entity.getPossibleCollisionBoxes(entityBox, scratch));
            if (possible.expand(2.0D).isIntersected(query)) return true;
        }
        return false;
    }
}
