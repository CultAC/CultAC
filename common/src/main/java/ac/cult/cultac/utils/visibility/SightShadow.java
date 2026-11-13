package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;

/**
 * The points a box hides from a point: those X for which the segment from the point to X meets the box, as up to
 * {@link #MAX_ROWS} rows n.(X - origin) + c <= 0 with unit normals. They are OcclusionGeometry's shadow rows, in
 * doubles.
 */
final class SightShadow {
    static final int MAX_ROWS = 13;
    /** The doubles one shadow's rows may take: four a row. */
    static final int SPAN = MAX_ROWS * 4;
    // An origin this near the line through one of the box's edges gives a plane whose direction cannot be told.
    private static final double MIN_NORMAL = 1.0E-7;

    private SightShadow() {}

    /**
     * Writes the rows of {@code box}'s shadow from {@code origin} into {@code rows} from {@code at}, four doubles
     * each, and returns their count; none when the origin is inside the box, which hides everything from it. -1 when
     * the shadow cannot be told and the box must count as hiding nothing.
     */
    static int rows(double[] origin, SimpleCollisionBox box, double[] rows, int at) {
        // X - origin = lambda * (a point of the box - origin) for some lambda >= 1. Each side of the box bounds lambda:
        // r_i <= high_i * lambda has normal +e_i and scale -high_i, r_i >= low_i * lambda normal -e_i and scale low_i.
        // A positive scale bounds lambda from above and a negative one from below, and every lower bound must lie
        // under every upper one.
        double[] scale = {
            origin[0] - box.maxX, box.minX - origin[0],
            origin[1] - box.maxY, box.minY - origin[1],
            origin[2] - box.maxZ, box.minZ - origin[2]
        };
        int count = 0;
        for (int upper = 0; upper < 6; upper++) {
            double upperScale = scale[upper];
            if (!(upperScale > 0)) continue;
            int upperAxis = upper >> 1;
            double upperNormal = (upper & 1) == 0 ? 1 : -1;
            // Under lambda >= 1: X lies beyond the box's near side.
            int row = at + 4 * count++;
            rows[row] = rows[row + 1] = rows[row + 2] = 0;
            rows[row + upperAxis] = upperNormal;
            rows[row + 3] = upperScale;
            for (int lower = 0; lower < 6; lower++) {
                double lowerScale = scale[lower];
                // A pair on one axis only says X lies on the box's side of the origin, which the near side does.
                if (!(lowerScale < 0) || lower >> 1 == upperAxis) continue;
                double lowerNormal = (lower & 1) == 0 ? 1 : -1;
                row = at + 4 * count++;
                rows[row] = rows[row + 1] = rows[row + 2] = rows[row + 3] = 0;
                rows[row + (lower >> 1)] += lowerNormal * upperScale;
                rows[row + upperAxis] -= upperNormal * lowerScale;
                double length = Math.sqrt(
                        rows[row] * rows[row] + rows[row + 1] * rows[row + 1] + rows[row + 2] * rows[row + 2]);
                if (length < MIN_NORMAL) return -1;
                rows[row] /= length;
                rows[row + 1] /= length;
                rows[row + 2] /= length;
            }
        }
        // A side exactly level with the origin bounds X without lambda.
        for (int side = 0; side < 6; side++) {
            if (scale[side] != 0) continue;
            int row = at + 4 * count++;
            rows[row] = rows[row + 1] = rows[row + 2] = rows[row + 3] = 0;
            rows[row + (side >> 1)] = (side & 1) == 0 ? 1 : -1;
        }
        return count;
    }
}
