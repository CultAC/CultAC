package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.blocksim.interaction.*;

/** Client-side cushion support; entity creation and consumption belong to the server. */
public final class CushionItemBehavior implements ItemBehavior {
    private final ItemBehavior fallback;
    private final EntityTypes.Type cushion;
    private final java.util.Set<String> collisionShapeTargets;
    public CushionItemBehavior(ac.cult.blocksim.data.DataTables data, ItemBehavior fallback, EntityTypes types) {
        this.fallback = fallback; cushion = types.byKey("minecraft:cushion");
        collisionShapeTargets = data.tags().getOrDefault("block:minecraft:cushion_uses_collision_shape", java.util.Set.of());
    }
    @Override public SimInteraction use(UseContext context) { return fallback.use(context); }
    @Override public SimInteraction useOn(UseContext context) {
        var level = context.level();
        var hit = context.hit();
        int state = level.stateAt(hit.pos());
        if (collisionShapeTargets.contains(level.registry().block(state).key())) {
            Vec3 from = context.player().sight().eyePosition(), ray = hit.location().subtract(from);
            double length = Math.sqrt(ray.lengthSquared());
            Vec3 to = length < 1.0E-5F ? hit.location() : hit.location().addScaled(
                new Vec3(ray.x() / length, ray.y() / length, ray.z() / length), .001);
            var collision = level.behavior(state).collisionShape(level, state, hit.pos()).clip(from, to, hit.pos());
            if (collision != null) hit = collision;
        }
        if (hit.face() != Direction.UP) return SimInteraction.FAIL;
        var placement = new PlacementContext(level, context.player(), context.hand(), context.stack(), hit);
        var pos = placement.clickedPos();
        Box box = cushion.spawnBoxAt(new Vec3(pos.x() + .5, hit.location().y(), pos.z() + .5));
        return canBePlacedAt(level, box) ? SimInteraction.SUCCESS : SimInteraction.FAIL;
    }
    public static boolean canBePlacedAt(SimLevel level, Box box) {
        Box anchor = new Box(box.minX(), box.minY() - 1.0 / 64, box.minZ(), Math.nextDown(box.maxX()), box.minY(), Math.nextDown(box.maxZ()));
        boolean supported = false;
        support:
        for (int z = (int) Math.floor(anchor.minZ()); z <= (int) Math.floor(anchor.maxZ()); z++)
            for (int y = (int) Math.floor(anchor.minY() - .125); y <= (int) Math.floor(anchor.maxY()); y++)
                for (int x = (int) Math.floor(anchor.minX()); x <= (int) Math.floor(anchor.maxX()); x++) {
                    var pos = new BlockPos(x, y, z);
                    int state = level.stateAt(pos);
                    var shape = level.behavior(state).outlineShape(level, state, pos, (ac.cult.blocksim.engine.SimPlayer) null);
                    if (!shape.isEmpty() && bounds(shape.boxes()).move(x, y, z).intersects(anchor)) {
                        supported = true;
                        break support;
                    }
                }
        if (!supported) return false;
        Box inside = box.nextDeflated();
        boolean covered = true;
        cover:
        for (int z = (int) Math.floor(inside.minZ()); z <= (int) Math.floor(inside.maxZ()); z++)
            for (int y = (int) Math.floor(inside.minY()); y <= (int) Math.floor(inside.maxY()); y++)
                for (int x = (int) Math.floor(inside.minX()); x <= (int) Math.floor(inside.maxX()); x++)
                    if (!level.registry().facts(level.stateAt(new BlockPos(x, y, z))).has(StateFacts.SUFFOCATING)) {
                        covered = false;
                        break cover;
                    }
        if (covered) return false;
        Box slice = new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.minY() + 1.0 / 64, box.maxZ()).nextDeflated();
        var exposed = Shapes.create(slice);
        for (var collider : BlockCollisions.intersecting(level, slice)) {
            exposed = Shapes.join(exposed, collider, BooleanOp.ONLY_FIRST);
            if (exposed.isEmpty()) return false;
        }
        return true;
    }
    private static Box bounds(java.util.List<Box> boxes) {
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (var box : boxes) {
            x0 = Math.min(x0, box.minX()); y0 = Math.min(y0, box.minY()); z0 = Math.min(z0, box.minZ());
            x1 = Math.max(x1, box.maxX()); y1 = Math.max(y1, box.maxY()); z1 = Math.max(z1, box.maxZ());
        }
        return new Box(x0, y0, z0, x1, y1, z1);
    }
}
