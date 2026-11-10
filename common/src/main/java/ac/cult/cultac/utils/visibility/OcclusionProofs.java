package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

/** Cheap sufficient proofs; unresolved geometry is handled by exact polygon clipping. */
final class OcclusionProofs {
    private OcclusionProofs() {}

    /** Null means neither visibility nor coverage has been proved. */
    static Boolean visible(
            Vec3 eye,
            SimpleCollisionBox target,
            Direction face,
            List<SimpleCollisionBox> blockers,
            List<SimpleCollisionBox> masks,
            double range) {
        if (!Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z) || !finite(target))
            return null;
        for (SimpleCollisionBox blocker : blockers) if (!finite(blocker)) return null;
        if (eye.x >= target.minX
                && eye.x <= target.maxX
                && eye.y >= target.minY
                && eye.y <= target.maxY
                && eye.z >= target.minZ
                && eye.z <= target.maxZ) return true;
        for (SimpleCollisionBox blocker : blockers) {
            if (covers(eye, target, blocker)) return false;
        }
        // Interaction override masks require their own exact crossing proof.
        if (masks != null) return null;
        for (int axis = 0; axis < 3; axis++) {
            double origin = coordinate(eye, axis);
            double entry;
            boolean positive;
            if (origin < min(target, axis)) {
                entry = min(target, axis);
                positive = false;
            } else if (origin > max(target, axis)) {
                entry = max(target, axis);
                positive = true;
            } else continue;
            if (face != null && face != OcclusionGeometry.direction(axis, positive)) continue;
            double x = clamp(eye.x, target.minX, target.maxX);
            double y = clamp(eye.y, target.minY, target.maxY);
            double z = clamp(eye.z, target.minZ, target.maxZ);
            Vec3 point = new Vec3(axis == 0 ? entry : x, axis == 1 ? entry : y, axis == 2 ? entry : z);
            if (!withinRange(eye, point, range)) continue;
            boolean clear = true;
            for (SimpleCollisionBox blocker : blockers) {
                if (mayIntersect(eye, point, blocker)) {
                    clear = false;
                    break;
                }
            }
            if (clear) return true;
        }
        return null;
    }

    static boolean covers(Vec3 eye, SimpleCollisionBox target, SimpleCollisionBox blocker) {
        for (int axis = 0; axis < 3; axis++) {
            double origin = coordinate(eye, axis);
            Interval distance;
            Interval plane;
            if (origin < min(blocker, axis) && min(target, axis) > min(blocker, axis)) {
                plane = Interval.difference(min(blocker, axis), origin);
                distance = Interval.relative(min(target, axis), max(target, axis), origin);
            } else if (origin > max(blocker, axis) && max(target, axis) < max(blocker, axis)) {
                plane = Interval.difference(origin, max(blocker, axis));
                distance = Interval.relative(min(target, axis), max(target, axis), origin)
                        .negate();
            } else continue;
            Interval scale = plane.dividePositive(distance);
            if (!scale.finite()) continue;
            boolean covered = true;
            for (int other = 0; other < 3; other++) {
                if (other == axis) continue;
                double eyeCoordinate = coordinate(eye, other);
                Interval projected = Interval.relative(min(target, other), max(target, other), eyeCoordinate)
                        .multiply(scale)
                        .add(eyeCoordinate);
                if (!projected.finite()
                        || projected.low < min(blocker, other)
                        || projected.high > max(blocker, other)) {
                    covered = false;
                    break;
                }
            }
            // Every ray to the target crosses this block face inside its box.
            if (covered) return true;
        }
        return false;
    }

    private static boolean withinRange(Vec3 eye, Vec3 point, double range) {
        if (!Double.isFinite(range)) return true;
        Interval distance = Interval.difference(point.x, eye.x)
                .square()
                .add(Interval.difference(point.y, eye.y).square())
                .add(Interval.difference(point.z, eye.z).square());
        return distance.finite() && distance.high < Math.nextDown(range * range);
    }

    /** A conservative closed-segment slab test: uncertainty always retains the blocker. */
    private static boolean mayIntersect(Vec3 eye, Vec3 point, SimpleCollisionBox blocker) {
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double from = coordinate(eye, axis), to = coordinate(point, axis);
            double low = min(blocker, axis), high = max(blocker, axis);
            if (Math.max(from, to) < low || Math.min(from, to) > high) return false;
            if (from == to) continue;
            Interval delta, near, far;
            if (to > from) {
                delta = Interval.difference(to, from);
                near = Interval.difference(low, from).dividePositive(delta);
                far = Interval.difference(high, from).dividePositive(delta);
            } else {
                delta = Interval.difference(from, to);
                near = Interval.difference(from, high).dividePositive(delta);
                far = Interval.difference(from, low).dividePositive(delta);
            }
            if (!near.finite() || !far.finite()) return true;
            enter = Math.max(enter, near.low);
            exit = Math.min(exit, far.high);
            if (enter > exit) return false;
        }
        return true;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static boolean finite(SimpleCollisionBox box) {
        return Double.isFinite(box.minX)
                && Double.isFinite(box.minY)
                && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX)
                && Double.isFinite(box.maxY)
                && Double.isFinite(box.maxZ);
    }

    private static double coordinate(Vec3 point, int axis) {
        return switch (axis) {
            case 0 -> point.x;
            case 1 -> point.y;
            default -> point.z;
        };
    }

    private static double min(SimpleCollisionBox box, int axis) {
        return switch (axis) {
            case 0 -> box.minX;
            case 1 -> box.minY;
            default -> box.minZ;
        };
    }

    private static double max(SimpleCollisionBox box, int axis) {
        return switch (axis) {
            case 0 -> box.maxX;
            case 1 -> box.maxY;
            default -> box.maxZ;
        };
    }

    /** Each primitive operation rounds outwards, enclosing the exact result on input doubles. */
    private record Interval(double low, double high) {
        private static final Interval UNKNOWN = new Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        static Interval difference(double left, double right) {
            return relative(left, left, right);
        }

        static Interval relative(double low, double high, double origin) {
            return new Interval(Math.nextDown(low - origin), Math.nextUp(high - origin));
        }

        boolean finite() {
            return Double.isFinite(low) && Double.isFinite(high);
        }

        Interval negate() {
            return new Interval(-high, -low);
        }

        Interval add(double value) {
            return new Interval(Math.nextDown(low + value), Math.nextUp(high + value));
        }

        Interval add(Interval other) {
            return new Interval(Math.nextDown(low + other.low), Math.nextUp(high + other.high));
        }

        Interval multiply(Interval other) {
            if (!finite() || !other.finite()) return UNKNOWN;
            double a = low * other.low, b = low * other.high, c = high * other.low, d = high * other.high;
            return new Interval(
                    Math.nextDown(Math.min(Math.min(a, b), Math.min(c, d))),
                    Math.nextUp(Math.max(Math.max(a, b), Math.max(c, d))));
        }

        Interval dividePositive(Interval other) {
            if (!finite() || !other.finite() || !(other.low > 0)) return UNKNOWN;
            double a = low / other.low, b = low / other.high, c = high / other.low, d = high / other.high;
            return new Interval(
                    Math.nextDown(Math.min(Math.min(a, b), Math.min(c, d))),
                    Math.nextUp(Math.max(Math.max(a, b), Math.max(c, d))));
        }

        Interval square() {
            if (!finite()) return UNKNOWN;
            double a = low * low, b = high * high;
            return new Interval(low <= 0 && high >= 0 ? 0 : Math.nextDown(Math.min(a, b)), Math.nextUp(Math.max(a, b)));
        }
    }
}
