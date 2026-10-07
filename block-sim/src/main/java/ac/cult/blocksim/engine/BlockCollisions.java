package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Placement queries follow the pinned BlockCollisions cursor bounds and boundary filters. */
public final class BlockCollisions {
    private BlockCollisions() { }

    public static List<VoxelShape> intersecting(SimLevel level, Box box) {
        var result = new ArrayList<VoxelShape>();
        visit(level, box, null, shape -> { result.add(shape); return false; });
        return List.copyOf(result);
    }

    public static boolean hasCollision(SimLevel level, Box box) {
        return hasCollision(level, box, null);
    }

    public static boolean hasCollision(SimLevel level, Box box, EntityCollisionContext context) {
        return visit(level, box, context, shape -> true);
    }

    private static boolean visit(SimLevel level, Box box, EntityCollisionContext context, Predicate<VoxelShape> visitor) {
        int minX = (int) Math.floor(box.minX() - 1.0E-7) - 1, maxX = (int) Math.floor(box.maxX() + 1.0E-7) + 1;
        int minY = (int) Math.floor(box.minY() - 1.0E-7) - 1, maxY = (int) Math.floor(box.maxY() + 1.0E-7) + 1;
        int minZ = (int) Math.floor(box.minZ() - 1.0E-7) - 1, maxZ = (int) Math.floor(box.maxZ() + 1.0E-7) + 1;
        VoxelShape query = Shapes.create(box);
        for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            int boundary = (x == minX || x == maxX ? 1 : 0) + (y == minY || y == maxY ? 1 : 0)
                + (z == minZ || z == maxZ ? 1 : 0);
            if (boundary == 3) continue;
            var pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) continue;
            int state = level.stateAt(pos);
            var facts = level.registry().facts(state);
            if (boundary == 1 && !facts.has(StateFacts.LARGE_COLLISION)) continue;
            if (boundary == 2 && !level.registry().block(state).key().equals("minecraft:moving_piston")) continue;
            var local = context == null ? level.behavior(state).collisionShape(level, state, pos)
                : level.behavior(state).collisionShape(level, state, pos, context);
            if (local.isEmpty()) continue;
            var shape = local.move(x, y, z);
            boolean intersects = local == Shapes.block()
                ? box.intersects(new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0))
                : Shapes.joinIsNotEmpty(shape, query, BooleanOp.AND);
            if (intersects && visitor.test(shape)) return true;
        }
        return false;
    }
}
