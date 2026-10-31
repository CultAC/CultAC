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
import java.util.function.UnaryOperator;

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
    public static final int POINT = 2;

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

    /**
     * {@code after}, the value {@code transform} gives {@code before}, ranged over every value it gives {@code before}'s
     * range. A transform that mixes axes (LivingEntity#updateFallFlyingMovement turns vertical speed into horizontal)
     * is sampled at the range's corners and, on an axis whose range spans zero, at zero, where its branches switch.
     */
    public static PredVector mapped(PredVector before, PredVector after, UnaryOperator<Vec3> transform) {
        SimpleCollisionBox range = before.getVelocityRange();
        if (range == null) return after;
        double[] xs = samples(before.x + range.minX, before.x + range.maxX);
        double[] ys = samples(before.y + range.minY, before.y + range.maxY);
        double[] zs = samples(before.z + range.minZ, before.z + range.maxZ);
        // A range need not hold its own value (a wall sample's range is one velocity beside it): then only the range's
        // values map.
        boolean holdsValue = range.minX <= 0
                && range.maxX >= 0
                && range.minY <= 0
                && range.maxY >= 0
                && range.minZ <= 0
                && range.maxZ >= 0;
        double minX = holdsValue ? after.x : Double.POSITIVE_INFINITY;
        double minY = holdsValue ? after.y : Double.POSITIVE_INFINITY;
        double minZ = holdsValue ? after.z : Double.POSITIVE_INFINITY;
        double maxX = holdsValue ? after.x : Double.NEGATIVE_INFINITY;
        double maxY = holdsValue ? after.y : Double.NEGATIVE_INFINITY;
        double maxZ = holdsValue ? after.z : Double.NEGATIVE_INFINITY;
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    Vec3 out = transform.apply(new Vec3(x, y, z));
                    minX = Math.min(minX, out.x);
                    minY = Math.min(minY, out.y);
                    minZ = Math.min(minZ, out.z);
                    maxX = Math.max(maxX, out.x);
                    maxY = Math.max(maxY, out.y);
                    maxZ = Math.max(maxZ, out.z);
                }
            }
        }
        return after.withMappedRange(new SimpleCollisionBox(
                minX - after.x, minY - after.y, minZ - after.z, maxX - after.x, maxY - after.y, maxZ - after.z));
    }

    private static double[] samples(double low, double high) {
        if (low == high) return new double[] {low};
        return low < 0 && high > 0 ? new double[] {low, 0, high} : new double[] {low, high};
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
            addWallEnds(player, context, velocity, outputs);
            addEdgeEnds(player, context, velocity, outputs);
            // A later pass's shove need not grow with the travel it moves, so fitting the report can miss: also run
            // the range's own velocity.
            if (hidden) outputs.add(velocity.withRangeEnds(POINT, POINT, POINT));
        }
        return outputs == null ? velocities : outputs;
    }

    /**
     * The ends Entity#move must try for one ranged {@code velocity} whose hidden axes are known: an end matters where
     * moving with it collides on that axis, or where the axis is hidden (a pass pinned it, an edge backoff may).
     */
    public static List<PredVector> ends(
            CultPlayer player,
            SimulationContext context,
            PredVector velocity,
            boolean hiddenX,
            boolean hiddenY,
            boolean hiddenZ) {
        SimpleCollisionBox range = velocity.getVelocityRange();
        if (range == null) return List.of(velocity);
        SimpleCollisionBox box = context.getFromActualPose();
        Vec3 stuck = context.getLastStuckSpeed();
        int[] xs = axisEnds(player, box, 0, velocity.x, range.minX, range.maxX, stuck.x, hiddenX);
        int[] ys = axisEnds(player, box, 1, velocity.y, range.minY, range.maxY, stuck.y, hiddenY);
        int[] zs = axisEnds(player, box, 2, velocity.z, range.minZ, range.maxZ, stuck.z, hiddenZ);
        var outputs = new ArrayList<PredVector>(xs.length * ys.length * zs.length);
        for (int x : xs) for (int y : ys) for (int z : zs) outputs.add(velocity.withRangeEnds(x, y, z));
        addWallEnds(player, context, velocity, outputs);
        addEdgeEnds(player, context, velocity, outputs);
        return outputs;
    }

    /**
     * A sneaking player's edge inside an X/Z range switches Player#maybeBackOffFromEdge between keeping the move and
     * backing it off, which keeps deltaMovement the position never showed. Per axis, also move with the velocity that
     * just stays on the edge, the one just past it, and the largest ones the backoff's 0.05 steps take back to nothing
     * (see {@link #edgeSwitch}).
     */
    private static void addEdgeEnds(
            CultPlayer player, SimulationContext context, PredVector velocity, List<PredVector> outputs) {
        // SneakEdgeBackoff#supportedTo checks the start stands on ground, as Player#isAboveGround does.
        if (!context.isSneaking() || player.isFlying || context.getVehicle() != null) return;
        SimpleCollisionBox range = velocity.getVelocityRange();
        SimpleCollisionBox box = context.getFromActualPose();
        for (int axis : new int[] {0, 2}) {
            double value = axis == 0 ? velocity.x : velocity.z;
            double min = axis == 0 ? range.minX : range.minZ;
            double max = axis == 0 ? range.maxX : range.maxZ;
            double stuck = axis == 0 ? context.getLastStuckSpeed().x : context.getLastStuckSpeed().z;
            if (min == max) continue;
            for (double far : new double[] {value + min, value + max}) {
                if (far == 0) continue;
                double edge = ac.cult.cultac.utils.nmsutil.SneakEdgeBackoff.supportedTo(
                        player, box, axis, far * stuck, player.getMaxUpStep());
                if (Double.isNaN(edge)) continue;
                for (int kind = 0; kind < 4; kind++) {
                    double at = edgeSwitch(edge, far, stuck, kind);
                    if (!(at > value + min && at < value + max)) continue;
                    outputs.add(new SwitchEnd(
                            velocity.withRangeEnds(0, 0, 0)
                                    .withMappedRange(new SimpleCollisionBox(
                                            axis == 0 ? at - value : range.minX,
                                            range.minY,
                                            axis == 2 ? at - value : range.minZ,
                                            axis == 0 ? at - value : range.maxX,
                                            range.maxY,
                                            axis == 2 ? at - value : range.maxZ)),
                            axis,
                            false,
                            far > value,
                            kind,
                            range));
                }
            }
        }
    }

    /**
     * Wall samples (see {@link #addWallEnds}) for the axes {@code glided}, {@code velocity}'s glide, ranges over though
     * {@code velocity} did not: {@link #atSwitch} pins each at its collision inside the glided range.
     */
    public static List<PredVector> glideWallEnds(
            CultPlayer player, SimulationContext context, PredVector velocity, PredVector glided) {
        SimpleCollisionBox before = velocity.getVelocityRange();
        SimpleCollisionBox after = glided.getVelocityRange();
        if (before == null || after == null) return List.of();
        double[] beforeMin = {before.minX, before.minY, before.minZ},
                beforeMax = {before.maxX, before.maxY, before.maxZ};
        double[] values = {glided.x, glided.y, glided.z};
        double[] mins = {after.minX, after.minY, after.minZ}, maxs = {after.maxX, after.maxY, after.maxZ};
        List<PredVector> outputs = new ArrayList<>(0);
        for (int axis = 0; axis < 3; axis++) {
            if (beforeMin[axis] != beforeMax[axis] || mins[axis] == maxs[axis]) continue;
            for (boolean otherFirst : axis == 1 ? new boolean[] {false} : new boolean[] {false, true}) {
                double[] wall = collisionInside(
                        player,
                        context,
                        axis,
                        values[axis] + mins[axis],
                        values[axis] + maxs[axis],
                        glided,
                        otherFirst);
                if (wall == null) continue;
                for (int side = 0; side < wall.length; side++)
                    outputs.add(new SwitchEnd(velocity.withRangeEnds(0, 0, 0), axis, true, otherFirst, side, before));
            }
        }
        return outputs;
    }

    /**
     * A wall or floor inside the range switches Entity#move between a free and a clipped move (a landing, a slime or
     * bed bounce, a zeroed axis), so neither end nor the value nearest the report covers the values beside it. Per
     * axis, also move with the velocity that just arrives at it and the one that just meets it.
     */
    private static void addWallEnds(
            CultPlayer player, SimulationContext context, PredVector velocity, List<PredVector> outputs) {
        SimpleCollisionBox range = velocity.getVelocityRange();
        double[] values = {velocity.x, velocity.y, velocity.z};
        double[] mins = {range.minX, range.minY, range.minZ};
        double[] maxs = {range.maxX, range.maxY, range.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (mins[axis] == maxs[axis]) continue;
            // Entity#collide moves Y first, then the larger horizontal axis: a horizontal wall can lie beyond either.
            for (boolean otherFirst : axis == 1 ? new boolean[] {false} : new boolean[] {false, true}) {
                double[] wall = collisionInside(
                        player,
                        context,
                        axis,
                        values[axis] + mins[axis],
                        values[axis] + maxs[axis],
                        velocity,
                        otherFirst);
                if (wall == null) continue;
                for (int side = 0; side < wall.length; side++) {
                    double[] low = mins.clone();
                    double[] high = maxs.clone();
                    low[axis] = high[axis] = wall[side] - values[axis];
                    outputs.add(new SwitchEnd(
                            velocity.withRangeEnds(0, 0, 0)
                                    .withMappedRange(
                                            new SimpleCollisionBox(low[0], low[1], low[2], high[0], high[1], high[2])),
                            axis,
                            true,
                            otherFirst,
                            side,
                            range));
                }
            }
        }
    }

    /**
     * A velocity at a switch inside one axis of its range: one that just arrives at (or just meets) a collision, or
     * just stays on (or just passes) a sneaking player's edge. A transform before the move that changes that axis
     * (LivingEntity#updateFallFlyingMovement) moves the switch inside the transformed range: {@link #atSwitch} finds it
     * again there.
     */
    public static final class SwitchEnd extends PredVector {
        private final int axis;
        private final boolean wall;
        // An edge: toward the range's high end. A wall: beyond the other horizontal axis's move (see #collisionInside).
        private final boolean high;
        // A wall: 0 just arrives, 1 just meets it. An edge: see #edgeSwitch.
        private final int kind;
        private final SimpleCollisionBox range;

        private SwitchEnd(
                PredVector velocity, int axis, boolean wall, boolean high, int kind, SimpleCollisionBox range) {
            super(velocity, velocity, wall ? "collision switch" : "edge switch");
            this.axis = axis;
            this.wall = wall;
            this.high = high;
            this.kind = kind;
            this.range = range;
        }
    }

    private static final double BACKOFF_STEP = 0.05D;

    /**
     * A velocity at one of a sneaking edge's switches toward {@code far}, from the move {@code edge} that still stands
     * (SneakEdgeBackoff#supportedTo): 0 stays on the edge, 1 just passes it, 2 the largest move Player#maybeBackOffFromEdge
     * still takes back to nothing (it zeroes any unsupported move up to 0.05), 3 the largest such move in the range after
     * further 0.05 steps. NaN when there is none.
     */
    private static double edgeSwitch(double edge, double far, double stuck, int kind) {
        double sign = Math.signum(far);
        double steps = Math.floor(Math.abs(far * stuck) / BACKOFF_STEP);
        double move = switch (kind) {
            case 0 -> edge;
            case 1 -> edge + sign * 1.0E-7;
            case 2 -> Math.abs(edge) < BACKOFF_STEP ? sign * BACKOFF_STEP : Double.NaN;
            default -> Math.abs(edge) < BACKOFF_STEP && steps >= 2 ? sign * BACKOFF_STEP * steps : Double.NaN;
        };
        return move / stuck;
    }

    /**
     * {@code moved}, the transform's value for {@code before}, for a {@link SwitchEnd} sample: the transform of the
     * sample's whole range, with the sample's axis at the same switch inside it. Null when {@code before} is no switch
     * sample or the transformed range holds no such switch.
     */
    public static PredVector atSwitch(
            CultPlayer player,
            SimulationContext context,
            PredVector before,
            PredVector moved,
            UnaryOperator<Vec3> transform) {
        SwitchEnd sample = before.firstInLineage(SwitchEnd.class);
        if (sample == null) return null;
        PredVector whole = mapped(before.withMappedRange(sample.range), moved, transform);
        SimpleCollisionBox range = whole.getVelocityRange();
        if (range == null) return null;
        int axis = sample.axis;
        double value = axis == 0 ? whole.x : axis == 1 ? whole.y : whole.z;
        double low = value + (axis == 0 ? range.minX : axis == 1 ? range.minY : range.minZ);
        double high = value + (axis == 0 ? range.maxX : axis == 1 ? range.maxY : range.maxZ);
        double at;
        if (sample.wall) {
            double[] hit = collisionInside(player, context, axis, low, high, whole, sample.high);
            if (hit == null || sample.kind >= hit.length) return null;
            at = hit[sample.kind];
        } else {
            double far = sample.high ? high : low;
            double stuck = axis == 0 ? context.getLastStuckSpeed().x : context.getLastStuckSpeed().z;
            if (far == 0) return null;
            double edge = ac.cult.cultac.utils.nmsutil.SneakEdgeBackoff.supportedTo(
                    player, context.getFromActualPose(), axis, far * stuck, player.getMaxUpStep());
            if (Double.isNaN(edge)) return null;
            at = edgeSwitch(edge, far, stuck, sample.kind);
            if (!(at > low && at < high)) return null;
        }
        at -= value;
        return whole.withMappedRange(new SimpleCollisionBox(
                axis == 0 ? at : range.minX,
                axis == 1 ? at : range.minY,
                axis == 2 ? at : range.minZ,
                axis == 0 ? at : range.maxX,
                axis == 1 ? at : range.maxY,
                axis == 2 ? at : range.maxZ));
    }

    /**
     * Where moving along {@code axis} toward {@code low} or {@code high} first meets a collision, as a velocity, when that
     * lies strictly inside the range: {@code {velocity that just arrives, velocity that just meets it}}. Null when no
     * collision lies inside. Entity#collide moves Y first, then the larger of X and Z: a horizontal axis moves from the
     * actual-pose box after {@code value}'s Y move, and when {@code otherFirst} after its other horizontal move too.
     */
    public static double[] collisionInside(
            CultPlayer player,
            SimulationContext context,
            int axis,
            double low,
            double high,
            Vec3 value,
            boolean otherFirst) {
        SimpleCollisionBox box = context.getFromActualPose();
        Vec3 stuckSpeed = context.getLastStuckSpeed();
        double stuck = axis == 0 ? stuckSpeed.x : axis == 1 ? stuckSpeed.y : stuckSpeed.z;
        if (axis != 1) {
            double y = Collisions.collide(player, box, 0, value.y * stuckSpeed.y, 0, false).y;
            box = box.copy().offset(0, y, 0);
            if (otherFirst) {
                double other = axis == 0 ? value.z * stuckSpeed.z : value.x * stuckSpeed.x;
                Vec3 moved = Collisions.collide(player, box, axis == 2 ? other : 0, 0, axis == 0 ? other : 0, false);
                double shift = axis == 0 ? moved.z : moved.x;
                // Without the other move the probe is the one before it.
                if (shift == 0) return null;
                box = box.copy().offset(axis == 2 ? shift : 0, 0, axis == 0 ? shift : 0);
            }
        }
        // Entity#move zeroes a horizontal axis only past Mth.equal's 1.0E-5F; any vertical clip lands or bumps.
        double past = axis == 1 ? 1.0E-7 : 2.0E-5;
        for (double far : new double[] {low, high}) {
            if (far == 0) continue;
            double move = far * stuck;
            Vec3 attempted = new Vec3(axis == 0 ? move : 0, axis == 1 ? move : 0, axis == 2 ? move : 0);
            Vec3 collided = Collisions.collide(player, box, attempted.x, attempted.y, attempted.z, false);
            double reached = (axis == 0 ? collided.x : axis == 1 ? collided.y : collided.z) / stuck;
            if (reached == far || !(reached > low && reached < high)) continue;
            double meets = reached + Math.signum(far) * past / stuck;
            return meets > low && meets < high ? new double[] {reached, meets} : new double[] {reached};
        }
        return null;
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

    /**
     * {@code vector}'s range split where Entity#updateFluidHeightAndDoFluidPushing's minimum push starts to apply: only
     * while deltaMovement's X and Z are both under 0.003 does a weak current grow to 0.0045. One vector per part, each
     * holding the original's nearest value in it, so a later check of any one value decides its whole part. The vector
     * itself when its range lies on one side.
     */
    public static List<PredVector> splitAtSlowSquare(PredVector vector) {
        SimpleCollisionBox range = vector.getVelocityRange();
        if (range == null) return List.of(vector);
        double x0 = vector.x + range.minX, x1 = vector.x + range.maxX;
        double z0 = vector.z + range.minZ, z1 = vector.z + range.maxZ;
        double inLow = Math.nextUp(-SLOW), inHigh = Math.nextDown(SLOW);
        boolean meets = x0 <= inHigh && x1 >= inLow && z0 <= inHigh && z1 >= inLow;
        boolean inside = x0 >= inLow && x1 <= inHigh && z0 >= inLow && z1 <= inHigh;
        if (!meets || inside) return List.of(vector);
        var parts = new ArrayList<PredVector>(5);
        double midLow = Math.max(x0, inLow), midHigh = Math.min(x1, inHigh);
        part(parts, vector, range, x0, Math.min(x1, -SLOW), z0, z1);
        part(parts, vector, range, Math.max(x0, SLOW), x1, z0, z1);
        part(parts, vector, range, midLow, midHigh, z0, Math.min(z1, -SLOW));
        part(parts, vector, range, midLow, midHigh, Math.max(z0, SLOW), z1);
        part(parts, vector, range, midLow, midHigh, Math.max(z0, inLow), Math.min(z1, inHigh));
        return parts;
    }

    private static final double SLOW = 0.003D;

    private static void part(
            List<PredVector> parts,
            PredVector vector,
            SimpleCollisionBox range,
            double minX,
            double maxX,
            double minZ,
            double maxZ) {
        if (minX > maxX || minZ > maxZ) return;
        double x = Math.max(minX, Math.min(maxX, vector.x)), z = Math.max(minZ, Math.min(maxZ, vector.z));
        parts.add(vector.withXYZ(x, vector.y, z, "fluid current part")
                .withVelocityRange(
                        new SimpleCollisionBox(minX - x, range.minY, minZ - z, maxX - x, range.maxY, maxZ - z)));
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
