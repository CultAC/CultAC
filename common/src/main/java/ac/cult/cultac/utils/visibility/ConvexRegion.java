package ac.cult.cultac.utils.visibility;

import java.util.ArrayList;
import java.util.List;

/** A bounded convex set, retaining open boundaries and zero-dimensional intersections. */
final class ConvexRegion {
    record Point(ExactFraction x, ExactFraction y) {
        Point interpolate(Point other, ExactFraction scale) {
            return new Point(
                    x.add(other.x.subtract(x).multiply(scale)),
                    y.add(other.y.subtract(y).multiply(scale)));
        }

        boolean same(Point other) {
            return x.compareTo(other.x) == 0 && y.compareTo(other.y) == 0;
        }
    }

    /** a*x + b*y + c <= 0, or < 0 when strict. */
    record HalfPlane(ExactFraction a, ExactFraction b, ExactFraction c, boolean strict) {
        ExactFraction value(Point point) {
            return a.multiply(point.x).add(b.multiply(point.y)).add(c);
        }

        int signum(Point point) {
            double ax0 = a.lowerBound() * point.x.lowerBound(), ax1 = a.lowerBound() * point.x.upperBound();
            double ax2 = a.upperBound() * point.x.lowerBound(), ax3 = a.upperBound() * point.x.upperBound();
            double by0 = b.lowerBound() * point.y.lowerBound(), by1 = b.lowerBound() * point.y.upperBound();
            double by2 = b.upperBound() * point.y.lowerBound(), by3 = b.upperBound() * point.y.upperBound();
            double lowX = Math.nextDown(Math.min(Math.min(ax0, ax1), Math.min(ax2, ax3)));
            double highX = Math.nextUp(Math.max(Math.max(ax0, ax1), Math.max(ax2, ax3)));
            double lowY = Math.nextDown(Math.min(Math.min(by0, by1), Math.min(by2, by3)));
            double highY = Math.nextUp(Math.max(Math.max(by0, by1), Math.max(by2, by3)));
            double low = Math.nextDown(Math.nextDown(lowX + lowY) + c.lowerBound());
            double high = Math.nextUp(Math.nextUp(highX + highY) + c.upperBound());
            if (low > 0) return 1;
            if (high < 0) return -1;
            // Rounded bounds straddle the plane, including exact edge/corner hits.
            return value(point).signum();
        }

        HalfPlane complement() {
            return new HalfPlane(a.negate(), b.negate(), c.negate(), !strict);
        }
    }

    private static final List<HalfPlane> ORIGIN_PLANES = List.of(
            new HalfPlane(ExactFraction.ONE, ExactFraction.ZERO, ExactFraction.ZERO, false),
            new HalfPlane(ExactFraction.MINUS_ONE, ExactFraction.ZERO, ExactFraction.ZERO, false),
            new HalfPlane(ExactFraction.ZERO, ExactFraction.ONE, ExactFraction.ZERO, false),
            new HalfPlane(ExactFraction.ZERO, ExactFraction.MINUS_ONE, ExactFraction.ZERO, false));

    private final List<Point> vertices;
    private final List<HalfPlane> openBoundaries;

    private ConvexRegion(List<Point> vertices, List<HalfPlane> openBoundaries) {
        this.vertices = vertices;
        this.openBoundaries = openBoundaries;
    }

    static ConvexRegion rectangle(ExactFraction minX, ExactFraction minY, ExactFraction maxX, ExactFraction maxY) {
        List<Point> points = new ArrayList<>();
        append(points, new Point(minX, minY));
        append(points, new Point(maxX, minY));
        append(points, new Point(maxX, maxY));
        append(points, new Point(minX, maxY));
        close(points);
        return new ConvexRegion(points, List.of());
    }

    ConvexRegion clip(HalfPlane plane) {
        return clip(plane, signs(plane), false, null);
    }

    private int[] signs(HalfPlane plane) {
        int[] signs = new int[vertices.size()];
        for (int i = 0; i < signs.length; i++) signs[i] = plane.signum(vertices.get(i));
        return signs;
    }

    private ConvexRegion clip(HalfPlane plane, int[] signs, boolean flipped, Point[] crossings) {
        int minSign = 1, maxSign = -1;
        for (int sign : signs) {
            if (flipped) sign = -sign;
            minSign = Math.min(minSign, sign);
            maxSign = Math.max(maxSign, sign);
        }
        if (maxSign <= 0) {
            if (!plane.strict || maxSign < 0) return this;
            if (minSign == 0) return null;
            // The closed polygon is unchanged. Retain only the new open edge;
            // the existing boundaries are already valid on these same vertices.
            List<HalfPlane> boundaries = new ArrayList<>(openBoundaries);
            boundaries.add(plane);
            return new ConvexRegion(vertices, boundaries);
        }
        if (minSign > 0 || (plane.strict && minSign == 0)) return null;
        List<Point> output = new ArrayList<>();
        Point previous = vertices.get(vertices.size() - 1);
        int previousSign = flipped ? -signs[signs.length - 1] : signs[signs.length - 1];
        for (int i = 0; i < vertices.size(); i++) {
            Point current = vertices.get(i);
            int currentSign = flipped ? -signs[i] : signs[i];
            boolean previousInside = previousSign <= 0;
            boolean currentInside = currentSign <= 0;
            if (previousInside != currentInside) {
                Point intersection = crossings == null ? null : crossings[i];
                if (intersection == null) {
                    if (previousSign == 0) intersection = previous;
                    else if (currentSign == 0) intersection = current;
                    else {
                        ExactFraction previousValue = plane.value(previous);
                        ExactFraction currentValue = plane.value(current);
                        intersection = previous.interpolate(
                                current, previousValue.divide(previousValue.subtract(currentValue)));
                    }
                    if (crossings != null) crossings[i] = intersection;
                }
                append(output, intersection);
            }
            if (currentInside) append(output, current);
            previous = current;
            previousSign = currentSign;
        }
        if (output.isEmpty()) return null;
        close(output);
        List<HalfPlane> boundaries = openBoundaries;
        if (plane.strict) {
            boundaries = new ArrayList<>(boundaries);
            boundaries.add(plane);
        }
        ConvexRegion result = new ConvexRegion(output, boundaries);
        // All vertices already satisfy the closed version of every retained
        // boundary. Their mean satisfies all strict boundaries iff each boundary
        // has at least one strictly interior vertex; no rational mean is needed.
        for (HalfPlane boundary : boundaries) {
            boolean interior = false;
            for (Point point : output) {
                if (boundary.signum(point) < 0) {
                    interior = true;
                    break;
                }
            }
            if (!interior) return null;
        }
        return result;
    }

    List<ConvexRegion> subtract(List<HalfPlane> shadow) {
        List<ConvexRegion> outside = new ArrayList<>();
        ConvexRegion remaining = this;
        for (HalfPlane plane : shadow) {
            // Both sides use the same vertex signs and exact edge crossings.
            // Negating the plane changes their signs, not their intersections.
            int[] signs = remaining.signs(plane);
            Point[] crossings = new Point[signs.length];
            ConvexRegion part = remaining.clip(plane.complement(), signs, true, crossings);
            if (part != null) outside.add(part);
            remaining = remaining.clip(plane, signs, false, crossings);
            if (remaining == null) break;
        }
        return outside;
    }

    Point sample() {
        ExactFraction x = ExactFraction.ZERO;
        ExactFraction y = ExactFraction.ZERO;
        for (Point point : vertices) {
            x = x.add(point.x);
            y = y.add(point.y);
        }
        ExactFraction size = ExactFraction.of(vertices.size());
        return new Point(x.divide(size), y.divide(size));
    }

    /** Whether the relative interior intersects the open disk x*x+y*y < radiusSquared. */
    boolean intersectsDisk(ExactFraction radiusSquared) {
        if (radiusSquared.signum() <= 0) return false;
        if (containsOrigin()) return true;
        for (int i = 0; i < vertices.size(); i++) {
            Point a = vertices.get(i);
            Point b = vertices.get((i + 1) % vertices.size());
            ExactFraction dx = b.x.subtract(a.x), dy = b.y.subtract(a.y);
            ExactFraction length = dx.multiply(dx).add(dy.multiply(dy));
            ExactFraction scale = length.signum() == 0
                    ? ExactFraction.ZERO
                    : a.x.multiply(dx).add(a.y.multiply(dy)).negate().divide(length);
            if (scale.signum() < 0) scale = ExactFraction.ZERO;
            if (scale.compareTo(ExactFraction.ONE) > 0) scale = ExactFraction.ONE;
            Point nearest = a.interpolate(b, scale);
            if (nearest.x.multiply(nearest.x).add(nearest.y.multiply(nearest.y)).compareTo(radiusSquared) < 0)
                return true;
        }
        return false;
    }

    private boolean containsOrigin() {
        // All stored vertices are in convex boundary order. Handle the line/point
        // cases as well: clipping to both coordinates zero proves containment.
        ConvexRegion region = this;
        for (HalfPlane plane : ORIGIN_PLANES) {
            region = region.clip(plane);
            if (region == null) return false;
        }
        return true;
    }

    private static void append(List<Point> points, Point point) {
        if (points.isEmpty() || !point.same(points.get(points.size() - 1))) points.add(point);
    }

    private static void close(List<Point> points) {
        if (points.size() > 1 && points.get(0).same(points.get(points.size() - 1))) points.remove(points.size() - 1);
    }
}
