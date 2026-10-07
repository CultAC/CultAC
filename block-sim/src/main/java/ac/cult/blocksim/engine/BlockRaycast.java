package ac.cult.blocksim.engine;

import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.interaction.BlockHit;
import java.util.function.Function;

/** Shared outline raycast for item use. Null is a miss; unloaded reads propagate to the action boundary. */
public final class BlockRaycast {
    public enum Fluid { NONE, SOURCE_ONLY, ANY }
    @FunctionalInterface
    public interface ShapeQuery {
        ac.cult.blocksim.engine.shapes.VoxelShape get(int state, BlockPos pos);
    }
    public record Pick(BlockHit hit, boolean miss) { }
    private BlockRaycast() { }

    public static Pick playerPick(SimLevel level, SimPlayer player, Fluid fluids) {
        var sight = player.sight(); Vec3 from = sight.eyePosition();
        Vec3 to = viewEnd(player);
        var hit = clip(level, player, from, to, fluids);
        if (hit != null) return new Pick(hit, false);
        return new Pick(new BlockHit(new BlockPos((int) Math.floor(to.x()), (int) Math.floor(to.y()), (int) Math.floor(to.z())),
            Direction.approximateNearest(from.subtract(to)), to, false), true);
    }

    private static Vec3 viewEnd(SimPlayer player) {
        return player.sight().eyePosition().addScaled(viewVector(player), player.sight().blockInteractionRange());
    }

    public static Vec3 viewVector(SimPlayer player) {
        float pitch = player.state().pitch() * (float) (Math.PI / 180.0);
        float yaw = -player.state().yaw() * (float) (Math.PI / 180.0);
        float yawCos = VanillaMath.cos(yaw), yawSin = VanillaMath.sin(yaw), pitchCos = VanillaMath.cos(pitch), pitchSin = VanillaMath.sin(pitch);
        return new Vec3(yawSin * pitchCos, -pitchSin, yawCos * pitchCos);
    }

    /** BrushItem.calculateHitResult / ProjectileUtil.getHitResultOnViewVector, model 26.3. */
    public static boolean brushPicksBlock(SimLevel level, SimPlayer player) {
        Vec3 from = player.sight().eyePosition(), to = viewEnd(player);
        BlockHit hit = traverse(from, to, pos -> {
            int state = level.stateAt(pos);
            var behavior = level.behavior(state);
            var collision = behavior.collisionShape(level, state, pos, player.collision()).clip(from, to, pos);
            if (collision == null) return null;
            var interaction = behavior.interactionShape(level, state, pos).clip(from, to, pos);
            return interaction != null && from.distanceSquared(interaction.location()) < from.distanceSquared(collision.location())
                ? new BlockHit(pos, interaction.face(), collision.location(), collision.inside()) : collision;
        });
        Vec3 end = hit == null ? to : hit.location();
        Vec3 border = level.borderHit(from, end);
        if (border != null) end = border;
        return (hit != null || border != null) && !level.hasPickableEntityHit(from, end, player);
    }

    public static BlockHit clip(SimLevel level, SimPlayer player, Vec3 from, Vec3 to, Fluid fluids) {
        return clip(level, from, to,
            (state, pos) -> level.behavior(state).outlineShape(level, state, pos, player),
            (state, pos) -> {
                var fluid = level.fluidAt(pos);
                if (fluids == Fluid.NONE || fluid.isEmpty() || fluids == Fluid.SOURCE_ONLY && !fluid.isSource()) return Shapes.empty();
                return FluidQueries.shape(fluid, () -> level.fluidAt(pos.relative(Direction.UP)));
            });
    }

    /** BlockGetter.clip: shape selection belongs to the caller; interaction overrides only change the face. */
    public static BlockHit clip(SimLevel level, Vec3 from, Vec3 to, ShapeQuery blocks, ShapeQuery fluids) {
        return traverse(from, to, pos -> {
            int state = level.stateAt(pos);
            var block = blocks.get(state, pos).clip(from, to, pos);
            if (block != null) {
                var override = level.behavior(state).interactionShape(level, state, pos).clip(from, to, pos);
                if (override != null && override.location().distanceSquared(from) < block.location().distanceSquared(from)) {
                    block = new BlockHit(pos, override.face(), block.location(), block.inside());
                }
            }
            BlockHit liquid = fluids.get(state, pos).clip(from, to, pos);
            double blockDistance = block == null ? Double.MAX_VALUE : from.distanceSquared(block.location());
            double liquidDistance = liquid == null ? Double.MAX_VALUE : from.distanceSquared(liquid.location());
            return blockDistance <= liquidDistance ? block : liquid;
        });
    }

    /** BlockGetter.traverseBlocks: extended endpoints and Z/Y/X ties are part of the client contract. */
    public static <T> T traverse(Vec3 from, Vec3 to, Function<BlockPos, T> visitor) {
        if (from.equals(to)) return null;
        double[] start = {from.x(), from.y(), from.z()}, end = {to.x(), to.y(), to.z()};
        int[] cell = new int[3], sign = new int[3];
        double[] step = new double[3], next = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double extendedEnd = end[axis] + -1.0E-7 * (start[axis] - end[axis]);
            double extendedStart = start[axis] + -1.0E-7 * (end[axis] - start[axis]);
            cell[axis] = (int) Math.floor(extendedStart);
            double delta = extendedEnd - extendedStart;
            sign[axis] = delta == 0 ? 0 : delta > 0 ? 1 : -1;
            step[axis] = sign[axis] == 0 ? Double.MAX_VALUE : sign[axis] / delta;
            double fraction = extendedStart - (long) Math.floor(extendedStart);
            next[axis] = step[axis] * (sign[axis] > 0 ? 1.0 - fraction : fraction);
        }
        T hit = visitor.apply(new BlockPos(cell[0], cell[1], cell[2]));
        if (hit != null) return hit;
        while (next[0] <= 1.0 || next[1] <= 1.0 || next[2] <= 1.0) {
            int axis = next[0] < next[1] ? (next[0] < next[2] ? 0 : 2) : (next[1] < next[2] ? 1 : 2);
            cell[axis] += sign[axis]; next[axis] += step[axis];
            hit = visitor.apply(new BlockPos(cell[0], cell[1], cell[2]));
            if (hit != null) return hit;
        }
        return null;
    }
}
