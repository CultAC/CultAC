package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Rotation-independent segment visibility. Shadows are projected onto target
 * faces by eliminating a scale variable, without perspective division or area thresholds.
 * Client picking semantics belong to the caller; this class operates on exact supplied geometry.
 */
public final class OcclusionGeometry {
    private OcclusionGeometry() {}

    private record Row(ExactFraction[] normal, ExactFraction constant, ExactFraction scale) {}

    public static boolean fullyOccluded(Vec3 eye, SimpleCollisionBox target, List<SimpleCollisionBox> occluders) {
        return !hasVisiblePoint(eye, target, null, occluders, null, Double.POSITIVE_INFINITY);
    }

    /** One point in each remaining convex component; null face considers every entry face. */
    public static List<Vec3> visiblePoints(
            Vec3 eye, SimpleCollisionBox target, Direction face, List<SimpleCollisionBox> occluders) {
        List<Vec3> points = new ArrayList<>();
        findVisible(eye, target, face, occluders, null, null, Double.POSITIVE_INFINITY, points);
        return points;
    }

    /** Masks restrict a ray to crossing at least one supplied surface before the target. */
    public static boolean hasVisiblePoint(
            Vec3 eye,
            SimpleCollisionBox target,
            Direction face,
            List<SimpleCollisionBox> occluders,
            List<SimpleCollisionBox> masks,
            double range) {
        Boolean proof = OcclusionProofs.visible(eye, target, face, occluders, masks, range);
        return proof != null ? proof : findVisible(eye, target, face, occluders, masks, null, range, null);
    }

    /**
     * A visible point {@code p} whose ray {@code r = p - eye} satisfies {@code n·r <= 0} for every normal in
     * {@code rays}. The cheap proofs ignore that restriction, so this always clips exactly.
     */
    static boolean hasVisiblePointInCone(
            Vec3 eye,
            SimpleCollisionBox target,
            List<SimpleCollisionBox> occluders,
            List<ExactFraction[]> rays,
            double range) {
        return findVisible(eye, target, null, occluders, null, rays, range, null);
    }

    /** A null output requests only existence, avoiding later faces and witness construction. */
    private static boolean findVisible(
            Vec3 eye,
            SimpleCollisionBox target,
            Direction face,
            List<SimpleCollisionBox> occluders,
            List<SimpleCollisionBox> masks,
            List<ExactFraction[]> rays,
            double range,
            List<Vec3> visible) {
        ExactFraction[] origin = {ExactFraction.of(eye.x), ExactFraction.of(eye.y), ExactFraction.of(eye.z)};
        ExactFraction[] low = relative(target, origin, false);
        ExactFraction[] high = relative(target, origin, true);
        if (containsOrigin(low, high)) {
            if (visible != null) visible.add(eye);
            return true;
        }

        List<List<Row>> shadows = new ArrayList<>(occluders.size());
        for (SimpleCollisionBox box : occluders)
            shadows.add(shadow(relative(box, origin, false), relative(box, origin, true)));
        boolean found = false;
        for (int axis = 0; axis < 3; axis++) {
            boolean positive;
            ExactFraction coordinate;
            if (low[axis].signum() > 0) {
                coordinate = low[axis];
                positive = false;
            } else if (high[axis].signum() < 0) {
                coordinate = high[axis];
                positive = true;
            } else continue;
            if (face != null && face != direction(axis, positive)) continue;

            int first = (axis + 1) % 3;
            int second = (axis + 2) % 3;
            List<ConvexRegion> regions =
                    List.of(ConvexRegion.rectangle(low[first], low[second], high[first], high[second]));
            if (masks != null) {
                List<ConvexRegion> selected = new ArrayList<>();
                for (SimpleCollisionBox mask : masks) {
                    ConvexRegion region = regions.get(0);
                    for (Row row : shadow(relative(mask, origin, false), relative(mask, origin, true))) {
                        region = region.clip(new ConvexRegion.HalfPlane(
                                row.normal[first],
                                row.normal[second],
                                row.constant.add(row.normal[axis].multiply(coordinate)),
                                false));
                        if (region == null) break;
                    }
                    if (region != null) selected.add(region);
                }
                regions = selected;
            }
            if (rays != null) {
                // On this face r[axis] = coordinate, so each n·r <= 0 is a half-plane in the face's coordinates.
                List<ConvexRegion> bounded = new ArrayList<>();
                for (ConvexRegion region : regions) {
                    for (ExactFraction[] normal : rays) {
                        region = region.clip(new ConvexRegion.HalfPlane(
                                normal[first], normal[second], normal[axis].multiply(coordinate), false));
                        if (region == null) break;
                    }
                    if (region != null) bounded.add(region);
                }
                regions = bounded;
            }
            for (List<Row> shadow : shadows) {
                List<ConvexRegion.HalfPlane> planes = new ArrayList<>(shadow.size());
                for (Row row : shadow)
                    planes.add(new ConvexRegion.HalfPlane(
                            row.normal[first],
                            row.normal[second],
                            row.constant.add(row.normal[axis].multiply(coordinate)),
                            false));
                List<ConvexRegion> next = new ArrayList<>();
                for (ConvexRegion region : regions) next.addAll(region.subtract(planes));
                regions = next;
                if (regions.isEmpty()) break;
            }
            for (ConvexRegion region : regions) {
                if (Double.isFinite(range)) {
                    ExactFraction radius = ExactFraction.of(range);
                    if (!region.intersectsDisk(radius.multiply(radius).subtract(coordinate.multiply(coordinate))))
                        continue;
                }
                if (visible == null) return true;
                found = true;
                ConvexRegion.Point point = region.sample();
                ExactFraction[] position = origin.clone();
                position[axis] = position[axis].add(coordinate);
                position[first] = position[first].add(point.x());
                position[second] = position[second].add(point.y());
                visible.add(new Vec3(position[0].doubleValue(), position[1].doubleValue(), position[2].doubleValue()));
            }
        }
        return found;
    }

    private static ExactFraction[] relative(SimpleCollisionBox box, ExactFraction[] eye, boolean upper) {
        double[] bounds =
                upper ? new double[] {box.maxX, box.maxY, box.maxZ} : new double[] {box.minX, box.minY, box.minZ};
        ExactFraction[] result = new ExactFraction[3];
        for (int i = 0; i < 3; i++) result[i] = ExactFraction.of(bounds[i]).subtract(eye[i]);
        return result;
    }

    private static List<Row> shadow(ExactFraction[] low, ExactFraction[] high) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(zeros(), ExactFraction.ONE, ExactFraction.ONE.negate())); // lambda >= 1
        for (int axis = 0; axis < 3; axis++) {
            ExactFraction[] positive = zeros();
            positive[axis] = ExactFraction.ONE;
            rows.add(new Row(positive, ExactFraction.ZERO, high[axis].negate()));
            ExactFraction[] negative = zeros();
            negative[axis] = ExactFraction.ONE.negate();
            rows.add(new Row(negative, ExactFraction.ZERO, low[axis]));
        }
        List<Row> result = new ArrayList<>();
        for (Row row : rows) if (row.scale.signum() == 0) result.add(row);
        // Fourier-Motzkin: each lower bound on lambda must be <= every upper bound.
        for (Row lower : rows) {
            if (lower.scale.signum() >= 0) continue;
            for (Row upper : rows) {
                if (upper.scale.signum() <= 0) continue;
                ExactFraction[] normal = zeros();
                for (int axis = 0; axis < 3; axis++)
                    normal[axis] =
                            lower.normal[axis].multiply(upper.scale).subtract(upper.normal[axis].multiply(lower.scale));
                result.add(new Row(
                        normal,
                        lower.constant.multiply(upper.scale).subtract(upper.constant.multiply(lower.scale)),
                        ExactFraction.ZERO));
            }
        }
        return result;
    }

    private static boolean containsOrigin(ExactFraction[] low, ExactFraction[] high) {
        for (int axis = 0; axis < 3; axis++) if (low[axis].signum() > 0 || high[axis].signum() < 0) return false;
        return true;
    }

    private static ExactFraction[] zeros() {
        return new ExactFraction[] {ExactFraction.ZERO, ExactFraction.ZERO, ExactFraction.ZERO};
    }

    static Direction direction(int axis, boolean positive) {
        return switch (axis) {
            case 0 -> positive ? Direction.EAST : Direction.WEST;
            case 1 -> positive ? Direction.UP : Direction.DOWN;
            case 2 -> positive ? Direction.SOUTH : Direction.NORTH;
            default -> throw new IllegalArgumentException("Invalid axis");
        };
    }
}
