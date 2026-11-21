package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whether a target is hidden behind boxes from every one of a {@link SightEyes}, proved for all of them at once in a
 * fixed number of passes; false whenever that is not proved.
 *
 * <p>A box that hides a point from the corners of a convex cell of eyes hides it from every eye in the cell, so a
 * proof looks only at corners. First one box that hides the whole target from all of them is looked for. Failing
 * that, a target no larger than a point is judged from itself: the boxes' shadows from it must cover the eyes. A
 * larger one is first judged from its eight corners, which finds the eyes one box hides all of it from, and then, for
 * the eyes left, face by face: what each box hides from every corner of those eyes is cut out of the face, and the
 * target is hidden when nothing of a face they look at is left.
 *
 * <p>The geometry is in doubles. Every box first gives up {@link #SLACK}, so a target that is really seen is seen
 * across a patch that wide, and a shadow counts only a much smaller margin inside its planes: rounding cannot hide
 * what is seen. It can only leave something unproved.
 */
final class SightProof {
    private static final double SLACK = 1.0E-4;
    // A target no wider than twice this is judged as its centre, the boxes giving up its half-size.
    private static final double POINT = 1.0E-2;
    private static final double TINY = 1.0E-9;
    private static final int UNBUILT = -2;

    // Scratch space for the surfaces' cuts.
    private final double[] values = new double[64];
    private final double[][] planes = new double[64][3];
    private final SimpleCollisionBox target;
    private final List<SimpleCollisionBox> blockers;
    // The rows of blocker b's shadow from origin o start at (b * 8 + o) * SightShadow.SPAN.
    private final double[] rows;
    private final int[] counts;
    private final int[] at = new int[8], count = new int[8];

    private SightProof(SimpleCollisionBox target, List<SimpleCollisionBox> blockers) {
        this.target = target;
        this.blockers = blockers;
        rows = new double[blockers.size() * 8 * SightShadow.SPAN];
        counts = new int[blockers.size() * 8];
        Arrays.fill(counts, UNBUILT);
    }

    /** Whether no eye of {@code eyes} sees any of {@code target} past {@code boxes}. */
    static boolean hidden(SightEyes eyes, SimpleCollisionBox target, List<SimpleCollisionBox> boxes) {
        double half =
                Math.max(target.maxX - target.minX, Math.max(target.maxY - target.minY, target.maxZ - target.minZ)) / 2;
        boolean point = half <= POINT;
        List<SimpleCollisionBox> blockers = shrunk(boxes, point ? SLACK + half : SLACK);
        Vec3[] corners = eyes.corners();
        if (sees(eyes.middle(), target, blockers)) return false;
        for (Vec3 corner : corners) if (sees(corner, target, blockers)) return false;
        if (oneHides(corners, target, blockers)) return true;
        if (point) {
            Vec3 centre = new Vec3(
                    (target.minX + target.maxX) / 2, (target.minY + target.maxY) / 2, (target.minZ + target.maxZ) / 2);
            return new SightProof(null, moved(blockers, centre.x, centre.y, centre.z)).point(moved(eyes, centre));
        }
        // Everything is taken from the target's block, so the numbers stay small.
        Vec3 origin = new Vec3(Math.floor(target.minX), Math.floor(target.minY), Math.floor(target.minZ));
        List<SimpleCollisionBox> local = moved(blockers, origin.x, origin.y, origin.z);
        local.sort((left, right) -> Double.compare(volume(right), volume(left)));
        return new SightProof(
                        moved(List.of(target), origin.x, origin.y, origin.z).get(0), local)
                .box(moved(eyes, origin));
    }

    // ---- A point at the origin: the eyes hidden from it.

    private boolean point(SightEyes eyes) {
        SightSurface surface = surface(eyes);
        double[][] origins = {new double[3]};
        for (int b = 0; b < blockers.size(); b++) {
            if (!shadows(b, origins)) continue;
            surface.cut(origins, rows, at, count);
            if (surface.hidden()) return true;
        }
        return false;
    }

    // ---- A box: the eyes one blocker hides all of it from, then its faces from the eyes left.

    private boolean box(SightEyes eyes) {
        SightSurface surface = surface(eyes);
        double[][] ends = new double[8][];
        for (int c = 0; c < 8; c++) {
            ends[c] = new double[] {
                (c & 1) == 0 ? target.minX : target.maxX,
                (c & 2) == 0 ? target.minY : target.maxY,
                (c & 4) == 0 ? target.minZ : target.maxZ
            };
        }
        for (int b = 0; b < blockers.size(); b++) {
            if (!shadows(b, ends)) continue;
            surface.cut(ends, rows, at, count);
            if (surface.hidden()) return true;
        }

        // The eyes left, as the cell of t and h around them.
        double along = length(eyes.along()), up = length(eyes.up());
        double[] extent = surface.extent();
        double t0 = along < TINY ? 0 : Math.max(0, (extent[0] - TINY) / along);
        double t1 = along < TINY ? 1 : Math.min(1, (extent[2] + TINY) / along);
        double h0 = up < TINY ? 0 : Math.max(0, (extent[1] - TINY) / up);
        double h1 = up < TINY ? 1 : Math.min(1, (extent[3] + TINY) / up);

        return cell(eyes, t0, t1, h0, h1);
    }

    /** Whether the target is proved hidden from every eye between t0 and t1 and between h0 and h1. */
    private boolean cell(SightEyes eyes, double t0, double t1, double h0, double h1) {
        Vec3[] corners = eyes.corners(t0, t1, h0, h1);
        int size = corners.length;
        double[][] origins = new double[size][];
        double[] low = {target.minX, target.minY, target.minZ}, high = {target.maxX, target.maxY, target.maxZ};
        for (int i = 0; i < size; i++) {
            origins[i] = new double[] {corners[i].x, corners[i].y, corners[i].z};
            for (int axis = 0; axis < 3; axis++) {
                low[axis] = Math.min(low[axis], origins[i][axis]);
                high[axis] = Math.max(high[axis], origins[i][axis]);
            }
        }
        if (size > 1 && oneHides(corners, target, blockers)) return true;
        Arrays.fill(counts, UNBUILT);

        boolean entered = false;
        for (int face = 0; face < 6; face++) {
            int axis = face >> 1;
            boolean far = (face & 1) != 0;
            double plane = far ? max(target, axis) : min(target, axis);
            int front = 0;
            for (double[] origin : origins) if (far ? origin[axis] > plane : origin[axis] < plane) front++;
            // No sight line from eyes behind a face enters the target through it.
            if (front == 0) continue;
            entered = true;
            // Some of these eyes are behind the face, which the proof does not split them by.
            if (front < size) return false;
            int first = (axis + 1) % 3, second = (axis + 2) % 3;
            double[] p0 = new double[3], u = new double[3], v = new double[3];
            p0[axis] = plane;
            u[first] = 1;
            v[second] = 1;
            SightSurface surface = new SightSurface(
                    values,
                    planes,
                    p0,
                    u,
                    v,
                    SightPolygon.rectangle(
                            min(target, first), min(target, second), max(target, first), max(target, second)));
            for (int b = 0; b < blockers.size() && !surface.hidden(); b++) {
                SimpleCollisionBox blocker = blockers.get(b);
                if (blocker.maxX < low[0] || blocker.minX > high[0]) continue;
                if (blocker.maxY < low[1] || blocker.minY > high[1]) continue;
                if (blocker.maxZ < low[2] || blocker.minZ > high[2]) continue;
                // A blocker wholly behind the face's plane hides none of it.
                if (far ? max(blocker, axis) <= plane : min(blocker, axis) >= plane) continue;
                if (!shadows(b, origins)) continue;
                surface.cut(origins, rows, at, count);
            }
            if (!surface.hidden()) return false;
        }
        // Eyes in front of no face are inside the target.
        return entered;
    }

    /** Points {@link #at} and {@link #count} at blocker b's shadows from the origins; false when one cannot be told. */
    private boolean shadows(int b, double[][] origins) {
        for (int o = 0; o < origins.length; o++) {
            int slot = b * 8 + o;
            // A cell's shadows are kept across its faces.
            if (counts[slot] == UNBUILT) {
                counts[slot] = SightShadow.rows(origins[o], blockers.get(b), rows, slot * SightShadow.SPAN);
            }
            at[o] = slot * SightShadow.SPAN;
            count[o] = counts[slot];
            if (count[o] < 0) return false;
        }
        return true;
    }

    /** The eyes as a surface: x along their path and y up their heights, with a unit of room where they do not spread. */
    private SightSurface surface(SightEyes eyes) {
        double along = length(eyes.along()), up = length(eyes.up());
        double[] u = along < TINY ? new double[3] : unit(eyes.along(), along);
        double[] v = up < TINY ? new double[3] : unit(eyes.up(), up);
        Vec3 start = eyes.start();
        return new SightSurface(
                values,
                planes,
                new double[] {start.x, start.y, start.z},
                u,
                v,
                SightPolygon.rectangle(0, 0, along < TINY ? 1 : along, up < TINY ? 1 : up));
    }

    // ---- Proofs that need no shadows.

    private static boolean sees(Vec3 eye, SimpleCollisionBox target, List<SimpleCollisionBox> blockers) {
        return Boolean.TRUE.equals(
                OcclusionProofs.visible(eye, target, null, blockers, null, Double.POSITIVE_INFINITY));
    }

    /** One blocker hides the whole target from every corner, and so from every eye between them. */
    private static boolean oneHides(Vec3[] corners, SimpleCollisionBox target, List<SimpleCollisionBox> blockers) {
        for (SimpleCollisionBox blocker : blockers) {
            boolean all = true;
            for (Vec3 corner : corners) {
                if (!OcclusionProofs.covers(corner, target, blocker)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private static List<SimpleCollisionBox> shrunk(List<SimpleCollisionBox> boxes, double by) {
        List<SimpleCollisionBox> result = new ArrayList<>(boxes.size());
        for (SimpleCollisionBox box : boxes) {
            if (box.maxX - box.minX <= 2 * by || box.maxY - box.minY <= 2 * by || box.maxZ - box.minZ <= 2 * by)
                continue;
            result.add(new SimpleCollisionBox(
                    box.minX + by, box.minY + by, box.minZ + by, box.maxX - by, box.maxY - by, box.maxZ - by));
        }
        return result;
    }

    private static List<SimpleCollisionBox> moved(List<SimpleCollisionBox> boxes, double x, double y, double z) {
        List<SimpleCollisionBox> result = new ArrayList<>(boxes.size());
        for (SimpleCollisionBox box : boxes) {
            result.add(new SimpleCollisionBox(
                    box.minX - x, box.minY - y, box.minZ - z, box.maxX - x, box.maxY - y, box.maxZ - z));
        }
        return result;
    }

    private static SightEyes moved(SightEyes eyes, Vec3 origin) {
        return new SightEyes(eyes.start().subtract(origin), eyes.along(), eyes.up());
    }

    private static double[] unit(Vec3 vector, double length) {
        return new double[] {vector.x / length, vector.y / length, vector.z / length};
    }

    private static double length(Vec3 vector) {
        return Math.sqrt(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z);
    }

    private static double volume(SimpleCollisionBox box) {
        return (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
    }

    private static double min(SimpleCollisionBox box, int axis) {
        return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
    }

    private static double max(SimpleCollisionBox box, int axis) {
        return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
    }
}
