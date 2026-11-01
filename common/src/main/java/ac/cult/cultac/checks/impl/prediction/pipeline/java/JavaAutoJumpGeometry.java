package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** LocalPlayer#updateAutoJump's ordered CollisionGetter/BlockCollisions look-ahead queries. */
final class JavaAutoJumpGeometry {
    private JavaAutoJumpGeometry() {}

    static List<SimpleCollisionBox> collisions(CultPlayer player, SimpleCollisionBox query) {
        var result = new ArrayList<SimpleCollisionBox>();
        for (Box box : entityCollisions(player, query))
            result.add(
                    SimpleCollisionBox.between(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        result.addAll(blockCollisions(player, query));
        return result;
    }

    static boolean hasEntityCollision(CultPlayer player, SimpleCollisionBox query) {
        return !entityCollisions(player, query).isEmpty();
    }

    /** Whether every chunk the query and its neighbouring blocks reach is loaded. */
    static boolean loaded(CultPlayer player, SimpleCollisionBox query) {
        int x0 = ((int) Math.floor(query.minX) - 1) >> 4, x1 = ((int) Math.floor(query.maxX) + 1) >> 4;
        int z0 = ((int) Math.floor(query.minZ) - 1) >> 4, z1 = ((int) Math.floor(query.maxZ) + 1) >> 4;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                if (player.compensatedWorld.getChunk(x, z) == null) return false;
        return true;
    }

    private static List<Box> entityCollisions(CultPlayer player, SimpleCollisionBox query) {
        return new ClientEntityQueries(player)
                .playerCollisionBoxes(new Box(query.minX, query.minY, query.minZ, query.maxX, query.maxY, query.maxZ));
    }

    static List<SimpleCollisionBox> blockCollisions(CultPlayer player, SimpleCollisionBox query) {
        var result = new ArrayList<SimpleCollisionBox>();
        var boxes = new ArrayList<SimpleCollisionBox>();
        boolean resolvedDirect = false;
        boolean direct = false;
        int x0 = (int) Math.floor(query.minX - 1.0E-7) - 1, x1 = (int) Math.floor(query.maxX + 1.0E-7) + 1;
        int y0 = (int) Math.floor(query.minY - 1.0E-7) - 1, y1 = (int) Math.floor(query.maxY + 1.0E-7) + 1;
        int z0 = (int) Math.floor(query.minZ - 1.0E-7) - 1, z1 = (int) Math.floor(query.maxZ + 1.0E-7) + 1;
        // Cursor3D advances X, then Y, then Z. The first obstacle controls the jump height.
        for (int z = z0; z <= z1; z++)
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    if (player.compensatedWorld.getChunk(x >> 4, z >> 4) == null) continue;
                    int edge =
                            (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
                    if (edge == 3) continue;
                    int state = player.compensatedWorld.getBlockStateIdAt(x, y, z);
                    var facts = DataTables.defaults().registry().facts(state);
                    if (edge == 1 && !facts.has(StateFacts.LARGE_COLLISION)
                            || edge == 2 && !BlockIds.is(state, BlockIds.MOVING_PISTON)) continue;
                    // Scans that reach no shape lookup never read the client version.
                    if (!resolvedDirect) {
                        direct = !ClientBlockShapes.hasMovementAlternatives(player);
                        resolvedDirect = true;
                    }
                    var actor = BlockIds.is(state, BlockIds.POWDER_SNOW) ? JavaCollisionState.current(player) : null;
                    var alternative = direct
                            ? Optional.<CollisionBox>empty()
                            : ClientBlockShapes.movementAlternative(player, state, x, y, z, player.y);
                    if (alternative.isEmpty())
                        NativeBlockCollisionHelper.addIfAnyIntersects(
                                player, state, x, y, z, player.y, actor, query, boxes, result);
                    else
                        NativeBlockCollisionHelper.addIfAnyIntersects(
                                ClientBlockShapes.movement(player, state, x, y, z, player.y, actor, alternative),
                                query,
                                boxes,
                                result);
                }
        return result;
    }

    static List<SimpleCollisionBox> block(CultPlayer player, int x, int y, int z) {
        var result = new ArrayList<SimpleCollisionBox>();
        ClientBlockShapes.movement(player, player.compensatedWorld.getBlockStateIdAt(x, y, z), x, y, z, player.y)
                .downCast(result);
        return result;
    }

    static boolean intersects(SimpleCollisionBox box, Vec3 from, Vec3 to) {
        // AABB.intersects(Vec3, Vec3) tests the two-point bounds, not a ray clip.
        return box.intersects(
                Math.min(from.x, to.x),
                Math.min(from.y, to.y),
                Math.min(from.z, to.z),
                Math.max(from.x, to.x),
                Math.max(from.y, to.y),
                Math.max(from.z, to.z));
    }
}
