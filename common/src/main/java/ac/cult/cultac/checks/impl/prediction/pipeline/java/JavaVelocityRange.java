package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A bounded deltaMovement the client may hold, as an AABB of offsets around a candidate. Entity#move runs on one
 * concrete velocity per candidate: on each uncertain axis the value nearest the report, and the low or high end
 * where a collision (floor, ceiling, wall) or a later pass could hide it. Every such outcome is its own candidate,
 * so landing, stepping and NoFall see one exact move each. The next tick carries the hull of the velocities the
 * matching candidates left.
 */
public final class JavaVelocityRange {
    private static final double CUTOFF = 0.003D;
    private static final double END_MARGIN = 0.1D;
    // The range end that moves with the candidate's own velocity, without an offset.
    static final int POINT = 2;

    private JavaVelocityRange() {}

    /** The Minkowski sum of two offset ranges; either may be null. */
    public static SimpleCollisionBox add(SimpleCollisionBox a, SimpleCollisionBox b) {
        if (a == null) return b;
        if (b == null) return a;
        return new SimpleCollisionBox(
                a.minX + b.minX, a.minY + b.minY, a.minZ + b.minZ, a.maxX + b.maxX, a.maxY + b.maxY, a.maxZ + b.maxZ);
    }

    /** {@code vector}'s range after an X/Z/Y scale such as Player#causeExtraKnockback's damping. */
    public static PredVector scaled(PredVector vector, Vec3 scale) {
        SimpleCollisionBox range = vector.getVelocityRange();
        if (range == null) return vector;
        return vector.withVelocityRange(SimpleCollisionBox.between(
                range.minX * scale.x, range.minY * scale.y, range.minZ * scale.z,
                range.maxX * scale.x, range.maxY * scale.y, range.maxZ * scale.z));
    }

    /** {@code after}, which overwrote an axis of {@code before} with an exact value, keeps no range on that axis. */
    public static PredVector afterAxisWrite(PredVector before, PredVector after) {
        SimpleCollisionBox range = before.getVelocityRange();
        if (range == null || after == before) return after;
        return after.withVelocityRange(new SimpleCollisionBox(
                after.x == before.x ? range.minX : 0, range.minY, after.z == before.z ? range.minZ : 0,
                after.x == before.x ? range.maxX : 0, range.maxY, after.z == before.z ? range.maxZ : 0));
    }

    /** LivingEntity#aiStep's 0.003 cutoff applied to every velocity of {@code before}'s range. */
    public static PredVector afterThreshold(PredVector before, PredVector after, boolean horizontalLength) {
        SimpleCollisionBox range = before.getVelocityRange();
        if (range == null) return after;
        double[] x = {before.x + range.minX, before.x + range.maxX};
        double[] y = cut(before.y + range.minY, before.y + range.maxY);
        double[] z = {before.z + range.minZ, before.z + range.maxZ};
        if (horizontalLength) {
            // From 1.21.5 a player's X/Z are zeroed together while the horizontal length is below the cutoff.
            double nearX = Math.max(0, Math.max(x[0], -x[1]));
            double nearZ = Math.max(0, Math.max(z[0], -z[1]));
            double farX = Math.max(Math.abs(x[0]), Math.abs(x[1]));
            double farZ = Math.max(Math.abs(z[0]), Math.abs(z[1]));
            if (farX * farX + farZ * farZ < CUTOFF * CUTOFF) {
                x = new double[] {0, 0};
                z = new double[] {0, 0};
            } else if (nearX * nearX + nearZ * nearZ < CUTOFF * CUTOFF) {
                x = new double[] {Math.min(x[0], 0), Math.max(x[1], 0)};
                z = new double[] {Math.min(z[0], 0), Math.max(z[1], 0)};
            }
        } else {
            x = cut(x[0], x[1]);
            z = cut(z[0], z[1]);
        }
        return after.withVelocityRange(new SimpleCollisionBox(
                x[0] - after.x, y[0] - after.y, z[0] - after.z, x[1] - after.x, y[1] - after.y, z[1] - after.z));
    }

    private static double[] cut(double min, double max) {
        double low = Math.abs(min) < CUTOFF ? 0 : min;
        double high = Math.abs(max) < CUTOFF ? 0 : max;
        // Any velocity inside the cutoff becomes zero.
        if (min < CUTOFF && max > -CUTOFF) {
            low = Math.min(low, 0);
            high = Math.max(high, 0);
        }
        return new double[] {low, high};
    }

    /** LivingEntity#jumpFromGround's max(deltaMovement.y, jumpPower) over {@code before}'s range. */
    public static PredVector afterJump(PredVector before, PredVector jumped, double jumpPower) {
        SimpleCollisionBox range = before.getVelocityRange();
        if (range == null) return jumped;
        double low = Math.max(before.y + range.minY, jumpPower) - jumped.y;
        double high = Math.max(before.y + range.maxY, jumpPower) - jumped.y;
        return jumped.withVelocityRange(new SimpleCollisionBox(range.minX, low, range.minZ, range.maxX, high, range.maxZ));
    }

    /**
     * One candidate per combination of the ends Entity#move must try. An end only matters where moving with it
     * collides on that axis, or where a block-entity pass or edge backoff after the move could hide the axis.
     */
    public static List<PredVector> ends(CultPlayer player, SimulationContext context, List<PredVector> velocities) {
        List<PredVector> outputs = null;
        for (int i = 0; i < velocities.size(); i++) {
            PredVector velocity = velocities.get(i);
            SimpleCollisionBox range = velocity.getVelocityRange();
            if (range == null) {
                if (outputs != null) outputs.add(velocity);
                continue;
            }
            if (outputs == null) outputs = new ArrayList<>(velocities.subList(0, i));
            // A 26.3 block-entity pass after travel can hide any axis; Player#maybeBackOffFromEdge only X/Z.
            boolean hidden = context.getPassAfterTravel() != null;
            boolean hiddenXZ = hidden || context.isSneaking();
            SimpleCollisionBox box = context.getFromActualPose();
            Vec3 stuck = context.getLastStuckSpeed();
            int[] xs = axisEnds(player, box, 0, velocity.x, range.minX, range.maxX, stuck.x, hiddenXZ);
            int[] ys = axisEnds(player, box, 1, velocity.y, range.minY, range.maxY, stuck.y, hidden);
            int[] zs = axisEnds(player, box, 2, velocity.z, range.minZ, range.maxZ, stuck.z, hiddenXZ);
            for (int x : xs) for (int y : ys) for (int z : zs) outputs.add(velocity.withRangeEnds(x, y, z));
            // A later pass's shove need not grow with the travel it moves, so fitting the report can miss: also run
            // the range's own velocity.
            if (hidden) outputs.add(velocity.withRangeEnds(POINT, POINT, POINT));
        }
        return outputs == null ? velocities : outputs;
    }

    private static int[] axisEnds(
            CultPlayer player,
            SimpleCollisionBox box,
            int axis,
            double value,
            double min,
            double max,
            double stuck,
            boolean hidden) {
        if (min == max) return new int[] {0};
        // The split runs before the travel handlers (glide, boat, fluid), which shift the move a little: an end
        // matters if a collision lies within that margin beyond it.
        boolean low = hidden || collides(player, box, axis, (value + min - END_MARGIN) * stuck);
        boolean high = hidden || collides(player, box, axis, (value + max + END_MARGIN) * stuck);
        if (low && high) return new int[] {0, -1, 1};
        if (low) return new int[] {0, -1};
        if (high) return new int[] {0, 1};
        return new int[] {0};
    }

    private static boolean collides(CultPlayer player, SimpleCollisionBox box, int axis, double move) {
        if (move == 0) return false;
        Vec3 attempted = new Vec3(axis == 0 ? move : 0, axis == 1 ? move : 0, axis == 2 ? move : 0);
        Vec3 collided = Collisions.collide(player, box, attempted.x, attempted.y, attempted.z, false);
        return !collided.equals(attempted);
    }

    /** The velocity {@code candidate} moves with: per axis its chosen end, or the value nearest {@code target}. */
    public static PredVector select(PredVector candidate, Vec3 target) {
        SimpleCollisionBox range = candidate.getVelocityRange();
        if (range == null) return candidate;
        double x = pick(candidate.getRangeEnd(0), candidate.x, range.minX, range.maxX, target.x);
        double y = pick(candidate.getRangeEnd(1), candidate.y, range.minY, range.maxY, target.y);
        double z = pick(candidate.getRangeEnd(2), candidate.z, range.minZ, range.maxZ, target.z);
        return candidate.withXYZOrSelf(x, y, z, "velocity range");
    }

    private static double pick(int end, double value, double min, double max, double target) {
        if (end == POINT) return value;
        if (end < 0) return value + min;
        if (end > 0) return value + max;
        return Math.max(value + min, Math.min(value + max, target));
    }

    /** Whether some uncertain axis of {@code candidate} moves with the value nearest the report. */
    public static boolean fitsReport(PredVector candidate) {
        SimpleCollisionBox range = candidate.getVelocityRange();
        return range != null
                && candidate.getRangeEnd(0) != POINT
                && (range.minX != range.maxX && candidate.getRangeEnd(0) == 0
                        || range.minY != range.maxY && candidate.getRangeEnd(1) == 0
                        || range.minZ != range.maxZ && candidate.getRangeEnd(2) == 0);
    }

    /** {@code velocity} uncertain over the hull of {@code velocities}, or itself when they all agree. */
    public static Vec3 hull(Vec3 velocity, Collection<? extends Vec3> velocities) {
        double minX = velocity.x, minY = velocity.y, minZ = velocity.z;
        double maxX = minX, maxY = minY, maxZ = minZ;
        for (Vec3 other : velocities) {
            minX = Math.min(minX, other.x);
            minY = Math.min(minY, other.y);
            minZ = Math.min(minZ, other.z);
            maxX = Math.max(maxX, other.x);
            maxY = Math.max(maxY, other.y);
            maxZ = Math.max(maxZ, other.z);
        }
        if (minX == maxX && minY == maxY && minZ == maxZ) return velocity;
        PredVector carried = velocity instanceof PredVector vector ? vector : new PredVector(velocity);
        return carried.withVelocityRange(new SimpleCollisionBox(
                minX - velocity.x, minY - velocity.y, minZ - velocity.z,
                maxX - velocity.x, maxY - velocity.y, maxZ - velocity.z));
    }
}
