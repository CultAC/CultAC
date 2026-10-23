package ac.cult.blocksim.engine.shapes;

/** Paper/Moonrise's direct AABB/occupancy test, retaining vanilla's ambiguous merger cases. */
final class AabbVoxelIntersection {
    private AabbVoxelIntersection() { }

    static boolean intersects(VoxelShape shape, VoxelShape query, double x, double y, double z, boolean translated) {
        if (shape.isEmpty() || query.isEmpty()) return false;
        if (query.coordinateCount(0) != 2 || query.coordinateCount(1) != 2 || query.coordinateCount(2) != 2) {
            return Shapes.joinIsNotEmpty(translated ? shape.move(x, y, z) : shape, query, BooleanOp.AND);
        }
        return intersects(shape, query, query.coords[0][0], query.coords[1][0], query.coords[2][0],
                query.coords[0][1], query.coords[1][1], query.coords[2][1], x, y, z, translated);
    }

    /** The same test against {@code Shapes.create(minX, ..., maxZ)}, built only when the Boolean join needs it. */
    static boolean intersects(VoxelShape shape, double minX, double minY, double minZ, double maxX, double maxY,
                              double maxZ, double x, double y, double z, boolean translated) {
        if (maxX - minX < Shapes.EPSILON || maxY - minY < Shapes.EPSILON || maxZ - minZ < Shapes.EPSILON) return false;
        if (Shapes.findBits(minX, maxX) >= 0 && Shapes.findBits(minY, maxY) >= 0 && Shapes.findBits(minZ, maxZ) >= 0) {
            return intersects(shape, Shapes.create(minX, minY, minZ, maxX, maxY, maxZ), x, y, z, translated);
        }
        if (shape.isEmpty()) return false;
        return intersects(shape, null, minX, minY, minZ, maxX, maxY, maxZ, x, y, z, translated);
    }

    /** A null query stands for the bounds' one-cell, non-cube-grid {@code Shapes.create}. */
    private static boolean intersects(VoxelShape shape, VoxelShape query, double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ, double x, double y, double z,
                                      boolean translated) {
        if (!safeAxis(shape, 0, minX, maxX, x, translated)
                || !safeAxis(shape, 1, minY, maxY, y, translated)
                || !safeAxis(shape, 2, minZ, maxZ, z, translated)) {
            if (mergeable(shape, query, 0, minX, maxX, x, translated)
                    && mergeable(shape, query, 1, minY, maxY, y, translated)
                    && mergeable(shape, query, 2, minZ, maxZ, z, translated)) {
                return mergedCellsIntersect(shape, minX, minY, minZ, maxX, maxY, maxZ, x, y, z, translated);
            }
            if (query == null) query = Shapes.create(minX, minY, minZ, maxX, maxY, maxZ);
            return Shapes.joinIsNotEmpty(translated ? shape.move(x, y, z) : shape, query, BooleanOp.AND);
        }
        int fromX = Math.max(0, floor(shape.coords[0], minX + Shapes.EPSILON, x, translated));
        int toX = Math.min(shape.size(0), floor(shape.coords[0], maxX - Shapes.EPSILON, x, translated) + 1);
        int fromY = Math.max(0, floor(shape.coords[1], minY + Shapes.EPSILON, y, translated));
        int toY = Math.min(shape.size(1), floor(shape.coords[1], maxY - Shapes.EPSILON, y, translated) + 1);
        int fromZ = Math.max(0, floor(shape.coords[2], minZ + Shapes.EPSILON, z, translated));
        int toZ = Math.min(shape.size(2), floor(shape.coords[2], maxZ - Shapes.EPSILON, z, translated) + 1);
        if (fromX >= toX || fromY >= toY || fromZ >= toZ) return false;
        for (int ix = fromX; ix < toX; ix++) for (int iy = fromY; iy < toY; iy++) {
            if (shape.anyFullZ(ix, iy, fromZ, toZ)) return true;
        }
        return false;
    }

    private static int floor(double[] coordinates, double value, double offset, boolean translated) {
        int low = 0, high = coordinates.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            double coordinate = translated ? coordinates[middle] + offset : coordinates[middle];
            if (value < coordinate) high = middle;
            else low = middle + 1;
        }
        return low - 1;
    }

    private static boolean safeAxis(VoxelShape shape, int axis, double min, double max, double offset, boolean translated) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || max - min <= 2 * Shapes.EPSILON) return false;
        double previous = Double.NEGATIVE_INFINITY;
        for (double root : shape.coords[axis]) {
            double value = translated ? root + offset : root;
            // Paper assumes grid intervals exceed epsilon. A tighter grid must retain index merging.
            if (!Double.isFinite(value) || value - previous <= 2 * Shapes.EPSILON) return false;
            double rounding = 8 * Math.max(Math.ulp(value), Math.max(Math.ulp(min), Math.ulp(max)));
            // Addition/subtraction and IndirectMerger's endpoint coalescing differ at epsilon ties.
            if (Math.abs(Math.abs(value - min) - Shapes.EPSILON) <= rounding
                    || Math.abs(Math.abs(value - max) - Shapes.EPSILON) <= rounding) return false;
            previous = value;
        }
        return true;
    }

    /** Whether {@link #pairedCells} reproduces this axis's IndexMerge: finite, at most 64 cells, no cube-grid merger. */
    private static boolean mergeable(VoxelShape shape, VoxelShape query, int axis, double min, double max,
                                     double offset, boolean translated) {
        if (!translated && query != null && (shape == query || shape.cubeCoordinates(axis) && query.cubeCoordinates(axis))) {
            return false;
        }
        if (shape.size(axis) > Long.SIZE || !Double.isFinite(min) || !Double.isFinite(max)) return false;
        for (double root : shape.coords[axis]) if (!Double.isFinite(translated ? root + offset : root)) return false;
        return true;
    }

    /**
     * Shapes.joinIsNotEmpty(shape, query, AND) for a one-cell query: the AND holds exactly when an occupied cell
     * lies in the product of the shape cells each axis's merger pairs with the query's cell.
     */
    private static boolean mergedCellsIntersect(VoxelShape shape, double minX, double minY, double minZ, double maxX,
                                                double maxY, double maxZ, double x, double y, double z,
                                                boolean translated) {
        long cellsX = pairedCells(shape.coords[0], minX, maxX, x, translated);
        if (cellsX == 0) return false;
        long cellsY = pairedCells(shape.coords[1], minY, maxY, y, translated);
        if (cellsY == 0) return false;
        long cellsZ = pairedCells(shape.coords[2], minZ, maxZ, z, translated);
        if (cellsZ == 0) return false;
        for (long xs = cellsX; xs != 0; xs &= xs - 1) {
            int ix = Long.numberOfTrailingZeros(xs);
            for (long ys = cellsY; ys != 0; ys &= ys - 1) {
                int iy = Long.numberOfTrailingZeros(ys);
                for (long zs = cellsZ; zs != 0; zs &= zs - 1) {
                    if (shape.full(ix, iy, Long.numberOfTrailingZeros(zs))) return true;
                }
            }
        }
        return false;
    }

    /** IndexMerge.create(AND) on one axis against the query's {min, max}: the shape cells paired with query cell 0. */
    private static long pairedCells(double[] coordinates, double min, double max, double offset, boolean translated) {
        int size = coordinates.length - 1;
        double first = translated ? coordinates[0] + offset : coordinates[0];
        double last = translated ? coordinates[size] + offset : coordinates[size];
        if (last < min - Shapes.EPSILON || max < first - Shapes.EPSILON) return 0;
        if (size == 1 && Double.doubleToRawLongBits(first) == Double.doubleToRawLongBits(min)
                && Double.doubleToRawLongBits(last) == Double.doubleToRawLongBits(max)) return 1;
        long cells = 0;
        double previous = Double.NaN;
        boolean emitted = false;
        int ai = 0, bi = 0, pairedA = 0, pairedB = 0;
        while (ai <= size || bi < 2) {
            boolean aOut = ai > size, bOut = bi >= 2;
            double a = aOut ? 0.0 : translated ? coordinates[ai] + offset : coordinates[ai];
            boolean chooseA = !aOut && (bOut || a < (bi == 0 ? min : max) + Shapes.EPSILON);
            if (chooseA) {
                ai++;
                if (bi == 0 || bOut) continue;
            } else {
                bi++;
                if (ai == 0 || aOut) continue;
            }
            double next = chooseA ? a : bi == 1 ? min : max;
            if (!(previous >= next - Shapes.EPSILON)) {
                // The previous coordinate now starts a merged interval; its last-written pair is final.
                if (emitted && pairedB == 0 && pairedA >= 0 && pairedA < size) cells |= 1L << pairedA;
                emitted = true;
                previous = next;
            }
            pairedA = ai - 1;
            pairedB = bi - 1;
        }
        return cells;
    }
}
