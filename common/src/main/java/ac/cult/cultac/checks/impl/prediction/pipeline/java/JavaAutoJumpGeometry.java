package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientShapeBoxes;
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

    /** A test of one collision box's Y bounds. */
    interface Heights {
        boolean test(double minY, double maxY);
    }

    /** Whether any of {@link #blockCollisions}'s boxes passes {@code heights}, tested in that list's order. */
    static boolean anyBlockCollision(CultPlayer player, SimpleCollisionBox query, Heights heights) {
        CompensatedWorld world = player.compensatedWorld;
        boolean snapshot = world.isReadingSelectionSnapshot();
        int minHeight = world.getMinHeight();
        boolean plain = !player.isBedrockMovement() && query.getClass() == SimpleCollisionBox.class;
        List<SimpleCollisionBox> boxes = null, found = null;
        boolean resolvedDirect = false;
        boolean direct = false;
        CachedChunk column = null;
        boolean columnRead = false;
        int columnX = 0, columnZ = 0;
        int x0 = (int) Math.floor(query.minX - 1.0E-7) - 1, x1 = (int) Math.floor(query.maxX + 1.0E-7) + 1;
        int y0 = (int) Math.floor(query.minY - 1.0E-7) - 1, y1 = (int) Math.floor(query.maxY + 1.0E-7) + 1;
        int z0 = (int) Math.floor(query.minZ - 1.0E-7) - 1, z1 = (int) Math.floor(query.maxZ + 1.0E-7) + 1;
        for (int z = z0; z <= z1; z++)
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    if (!columnRead || x >> 4 != columnX || z >> 4 != columnZ) {
                        columnX = x >> 4;
                        columnZ = z >> 4;
                        column = world.getChunk(columnX, columnZ);
                        columnRead = true;
                    }
                    if (column == null) continue;
                    int edge =
                            (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
                    if (edge == 3) continue;
                    int state = snapshot ? world.getBlockStateIdAt(x, y, z) : stateAt(column, x, y - minHeight, z);
                    var facts = DataTables.defaults().registry().facts(state);
                    if (edge == 1 && !facts.has(StateFacts.LARGE_COLLISION)
                            || edge == 2 && !BlockIds.is(state, BlockIds.MOVING_PISTON)) continue;
                    if (!resolvedDirect) {
                        direct = !ClientBlockShapes.hasMovementAlternatives(player);
                        resolvedDirect = true;
                    }
                    var actor = BlockIds.is(state, BlockIds.POWDER_SNOW) ? JavaCollisionState.current(player) : null;
                    var alternative = direct
                            ? Optional.<CollisionBox>empty()
                            : ClientBlockShapes.movementAlternative(player, state, x, y, z, player.y);
                    CollisionBox collider;
                    if (alternative.isEmpty()) {
                        VoxelShape shape =
                                NativeBlockCollisionHelper.getCollisionShape(player, state, x, y, z, player.y, actor);
                        if (shape.isEmpty()) continue;
                        if (shape == Shapes.block() && plain) {
                            if (crosses(query, 0.0 + x, 0.0 + y, 0.0 + z, 1.0 + x, 1.0 + y, 1.0 + z)
                                    && heights.test(Math.min(0.0 + y, 1.0 + y), Math.max(0.0 + y, 1.0 + y)))
                                return true;
                            continue;
                        }
                        var parts = plain && shape.hasFiniteCoordinates() ? ClientShapeBoxes.cached(shape) : null;
                        if (parts != null) {
                            if (anyIntersects(parts, x, y, z, query) && anyHeight(parts, y, heights)) return true;
                            continue;
                        }
                        collider = player.isBedrockMovement()
                                ? NativeBlockCollisionHelper.fromShape(shape, x, y, z)
                                : new VoxelShapeCollisionBox(shape, shape == Shapes.block()).offset(x, y, z);
                    } else {
                        collider = ClientBlockShapes.movement(player, state, x, y, z, player.y, actor, alternative);
                    }
                    if (found == null) {
                        boxes = new ArrayList<>();
                        found = new ArrayList<>();
                    }
                    found.clear();
                    NativeBlockCollisionHelper.addIfAnyIntersects(collider, query, boxes, found);
                    for (int i = 0; i < found.size(); i++) {
                        var box = found.get(i);
                        if (heights.test(box.minY, box.maxY)) return true;
                    }
                }
        return false;
    }

    // CompensatedWorld#getBlockStateIdAt for a loaded column, {@code y} above the world's minimum height.
    private static int stateAt(CachedChunk column, int x, int y, int z) {
        try {
            if (y < 0 || (y >> 4) >= column.sectionCount()) return 0;
            CachedSection section = column.getSection(y >> 4);
            if (section != null) return section.getStateId(CachedChunk.index(x & 0xF, y & 0xF, z & 0xF));
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static boolean anyIntersects(List<Box> parts, int x, int y, int z, SimpleCollisionBox query) {
        for (int i = 0; i < parts.size(); i++) {
            var box = parts.get(i);
            double x1 = box.minX() + x, y1 = box.minY() + y, z1 = box.minZ() + z;
            double x2 = box.maxX() + x, y2 = box.maxY() + y, z2 = box.maxZ() + z;
            if (crosses(
                    query,
                    Math.min(x1, x2),
                    Math.min(y1, y2),
                    Math.min(z1, z2),
                    Math.max(x1, x2),
                    Math.max(y1, y2),
                    Math.max(z1, z2))) return true;
        }
        return false;
    }

    // SimpleCollisionBox#isIntersected of the query with a box of these bounds.
    private static boolean crosses(
            SimpleCollisionBox query, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return maxX - SimpleCollisionBox.COLLISION_EPSILON > query.minX
                && minX + SimpleCollisionBox.COLLISION_EPSILON < query.maxX
                && maxY - SimpleCollisionBox.COLLISION_EPSILON > query.minY
                && minY + SimpleCollisionBox.COLLISION_EPSILON < query.maxY
                && maxZ - SimpleCollisionBox.COLLISION_EPSILON > query.minZ
                && minZ + SimpleCollisionBox.COLLISION_EPSILON < query.maxZ;
    }

    private static boolean anyHeight(List<Box> parts, int y, Heights heights) {
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            double y1 = part.minY() + y, y2 = part.maxY() + y;
            if (heights.test(Math.min(y1, y2), Math.max(y1, y2))) return true;
        }
        return false;
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
