package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a block can lie between some eyes and a target: inside the hull of both. A box is kept when it meets that
 * hull's outline in each of the three views along an axis; a box that misses one of them is crossed by no sight line.
 */
final class SightBeam {
    // A box this far outside an outline's edge misses it.
    private static final double OUTSIDE = 1.0E-9;

    // For the view along each axis, the outline's edges as a*p + b*q + c <= 0 inside, p and q the two axes after it.
    private final double[][][] edges = new double[3][][];

    SightBeam(List<Vec3> eyes, SimpleCollisionBox target) {
        for (int along = 0; along < 3; along++) edges[along] = outline(eyes, target, (along + 1) % 3, (along + 2) % 3);
    }

    /** Whether the rectangle meets the outline in the view along {@code along}, on the two axes after it. */
    boolean meets(int along, double minP, double minQ, double maxP, double maxQ) {
        for (double[] edge : edges[along]) {
            double nearest = edge[0] * (edge[0] > 0 ? minP : maxP) + edge[1] * (edge[1] > 0 ? minQ : maxQ) + edge[2];
            if (nearest > OUTSIDE) return false;
        }
        return true;
    }

    /**
     * Joins boxes that share a whole face until none do, so that a wall hides as one box. At most {@code limit} boxes
     * come back, the largest.
     */
    static List<SimpleCollisionBox> merge(List<SimpleCollisionBox> boxes, int limit) {
        int count = boxes.size();
        double[] b = new double[count * 6];
        for (int k = 0; k < count; k++) {
            SimpleCollisionBox box = boxes.get(k);
            b[k * 6] = box.minX;
            b[k * 6 + 1] = box.minY;
            b[k * 6 + 2] = box.minZ;
            b[k * 6 + 3] = box.maxX;
            b[k * 6 + 4] = box.maxY;
            b[k * 6 + 5] = box.maxZ;
        }
        boolean joined = true;
        while (joined) {
            joined = false;
            for (int first = 0; first < count; first++) {
                for (int second = first + 1; second < count; second++) {
                    if (!join(b, first * 6, second * 6)) continue;
                    // The last box takes the joined one's place and is tried next.
                    count--;
                    System.arraycopy(b, count * 6, b, second * 6, 6);
                    second--;
                    joined = true;
                }
            }
        }
        List<SimpleCollisionBox> merged = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            merged.add(new SimpleCollisionBox(
                    b[k * 6], b[k * 6 + 1], b[k * 6 + 2], b[k * 6 + 3], b[k * 6 + 4], b[k * 6 + 5]));
        }
        if (count <= limit) return merged;
        merged.sort((left, right) -> Double.compare(volume(right), volume(left)));
        return new ArrayList<>(merged.subList(0, limit));
    }

    // Grows the box at p over the one at q when they span the same rectangle across an axis and touch along it.
    private static boolean join(double[] b, int p, int q) {
        for (int axis = 0; axis < 3; axis++) {
            int i = (axis + 1) % 3, j = (axis + 2) % 3;
            if (b[p + i] != b[q + i] || b[p + 3 + i] != b[q + 3 + i]) continue;
            if (b[p + j] != b[q + j] || b[p + 3 + j] != b[q + 3 + j]) continue;
            if (b[p + axis] > b[q + 3 + axis] || b[q + axis] > b[p + 3 + axis]) continue;
            b[p + axis] = Math.min(b[p + axis], b[q + axis]);
            b[p + 3 + axis] = Math.max(b[p + 3 + axis], b[q + 3 + axis]);
            return true;
        }
        return false;
    }

    private static double volume(SimpleCollisionBox box) {
        return (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
    }

    // The convex outline of the eyes and the target seen along an axis; one without area gives its bounds.
    private static double[][] outline(List<Vec3> eyes, SimpleCollisionBox target, int p, int q) {
        int count = eyes.size() + 4;
        double[] ps = new double[count], qs = new double[count];
        for (int k = 0; k < eyes.size(); k++) {
            ps[k] = coordinate(eyes.get(k), p);
            qs[k] = coordinate(eyes.get(k), q);
        }
        for (int k = 0; k < 4; k++) {
            ps[eyes.size() + k] = (k & 1) == 0 ? min(target, p) : max(target, p);
            qs[eyes.size() + k] = (k & 2) == 0 ? min(target, q) : max(target, q);
        }
        // Sorted by p, then q.
        for (int k = 1; k < count; k++) {
            double kp = ps[k], kq = qs[k];
            int before = k - 1;
            while (before >= 0 && (ps[before] > kp || (ps[before] == kp && qs[before] > kq))) {
                ps[before + 1] = ps[before];
                qs[before + 1] = qs[before];
                before--;
            }
            ps[before + 1] = kp;
            qs[before + 1] = kq;
        }
        // The lower side, then the upper, counter-clockwise.
        int[] hull = new int[2 * count];
        int size = 0;
        for (int k = 0; k < count; k++) {
            while (size >= 2 && turn(ps, qs, hull[size - 2], hull[size - 1], k) <= 0) size--;
            hull[size++] = k;
        }
        for (int k = count - 2, lower = size + 1; k >= 0; k--) {
            while (size >= lower && turn(ps, qs, hull[size - 2], hull[size - 1], k) <= 0) size--;
            hull[size++] = k;
        }
        size--;
        if (size < 3) {
            double minQ = qs[0], maxQ = qs[0];
            for (double value : qs) {
                minQ = Math.min(minQ, value);
                maxQ = Math.max(maxQ, value);
            }
            return new double[][] {{-1, 0, ps[0]}, {1, 0, -ps[count - 1]}, {0, -1, minQ}, {0, 1, -maxQ}};
        }
        double[][] edges = new double[size][3];
        for (int k = 0; k < size; k++) {
            int from = hull[k], to = hull[(k + 1) % size];
            // The inside is to the left of from -> to.
            double a = qs[to] - qs[from], b = ps[from] - ps[to];
            double length = Math.sqrt(a * a + b * b);
            edges[k][0] = a / length;
            edges[k][1] = b / length;
            edges[k][2] = -(a * ps[from] + b * qs[from]) / length;
        }
        return edges;
    }

    private static double turn(double[] ps, double[] qs, int origin, int a, int b) {
        return (ps[a] - ps[origin]) * (qs[b] - qs[origin]) - (qs[a] - qs[origin]) * (ps[b] - ps[origin]);
    }

    private static double coordinate(Vec3 point, int axis) {
        return axis == 0 ? point.x : axis == 1 ? point.y : point.z;
    }

    private static double min(SimpleCollisionBox box, int axis) {
        return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
    }

    private static double max(SimpleCollisionBox box, int axis) {
        return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
    }
}
