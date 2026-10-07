package ac.cult.blocksim.engine.shapes;

import ac.cult.blocksim.data.Box;
import java.util.BitSet;
import java.util.List;

/** Placement's vanilla shape subset, including discrete snapping and Boolean index merging. */
public final class Shapes {
    public static final double EPSILON = 1.0E-7;
    private static final VoxelShape EMPTY = new VoxelShape(new double[][]{{0.0}, {0.0}, {0.0}}, new boolean[3], false, new BitSet());
    private static final VoxelShape BLOCK;
    static { var cells = new BitSet(); cells.set(0); BLOCK = new VoxelShape(new double[][]{{0.0, 1.0}, {0.0, 1.0}, {0.0, 1.0}}, new boolean[]{true, true, true}, true, cells); }
    private Shapes() { }
    public static VoxelShape empty() { return EMPTY; }
    public static VoxelShape block() { return BLOCK; }

    public static VoxelShape create(Box box) { return create(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()); }

    public static VoxelShape create(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (maxX - minX < EPSILON || maxY - minY < EPSILON || maxZ - minZ < EPSILON) return EMPTY;
        int xb = findBits(minX, maxX), yb = findBits(minY, maxY), zb = findBits(minZ, maxZ);
        if (xb < 0 || yb < 0 || zb < 0) {
            var cells = new BitSet(); cells.set(0);
            return new VoxelShape(new double[][]{{minX, maxX}, {minY, maxY}, {minZ, maxZ}}, new boolean[3], false, cells);
        }
        if (xb == 0 && yb == 0 && zb == 0) return BLOCK;
        int xs = 1 << xb, ys = 1 << yb, zs = 1 << zb;
        int x0 = (int) Math.round(minX * xs), y0 = (int) Math.round(minY * ys), z0 = (int) Math.round(minZ * zs);
        int x1 = (int) Math.round(maxX * xs), y1 = (int) Math.round(maxY * ys), z1 = (int) Math.round(maxZ * zs);
        var cells = new BitSet();
        for (int x = x0; x < x1; x++) for (int y = y0; y < y1; y++) cells.set((x * ys + y) * zs + z0, (x * ys + y) * zs + z1);
        return new VoxelShape(new double[][]{cubeCoords(xs), cubeCoords(ys), cubeCoords(zs)}, new boolean[]{true, true, true}, true, cells);
    }

    static int findBits(double min, double max) {
        if (min < -EPSILON || max > 1.0000001) return -1;
        for (int bits = 0; bits <= 3; bits++) {
            int intervals = 1 << bits; double a = min * intervals, b = max * intervals;
            if (Math.abs(a - Math.round(a)) < EPSILON * intervals && Math.abs(b - Math.round(b)) < EPSILON * intervals) return bits;
        }
        return -1;
    }

    static double[] cubeCoords(int size) { var coords = new double[size + 1]; for (int i = 0; i <= size; i++) coords[i] = (double) i / size; return coords; }

    public static VoxelShape fromBoxes(List<Box> boxes) {
        VoxelShape result = EMPTY;
        for (Box box : boxes) result = join(result, create(box), BooleanOp.OR);
        return result;
    }

    public static VoxelShape join(VoxelShape first, VoxelShape second, BooleanOp op) { return joinUnoptimized(first, second, op).optimize(); }

    public static VoxelShape joinUnoptimized(VoxelShape first, VoxelShape second, BooleanOp op) {
        if (first == second) return op.apply(true, true) ? first : EMPTY;
        if (first.isEmpty()) return op.apply(false, true) ? second : EMPTY;
        if (second.isEmpty()) return op.apply(true, false) ? first : EMPTY;
        var mergers = merge(first, second, op); var cells = new BitSet();
        int ys = mergers[1].first.length, zs = mergers[2].first.length;
        for (int x = 0; x < mergers[0].first.length; x++) for (int y = 0; y < ys; y++) for (int z = 0; z < zs; z++) {
            if (op.apply(first.full(mergers[0].first[x], mergers[1].first[y], mergers[2].first[z]),
                    second.full(mergers[0].second[x], mergers[1].second[y], mergers[2].second[z]))) cells.set((x * ys + y) * zs + z);
        }
        boolean cube = mergers[0].discrete && mergers[1].discrete && mergers[2].discrete;
        return new VoxelShape(new double[][]{mergers[0].coords, mergers[1].coords, mergers[2].coords},
                new boolean[]{mergers[0].cube, mergers[1].cube, mergers[2].cube}, cube, cells);
    }

    public static boolean joinIsNotEmpty(VoxelShape first, VoxelShape second, BooleanOp op) {
        if (first.isEmpty() || second.isEmpty()) return op.apply(!first.isEmpty(), !second.isEmpty());
        if (first == second) return op.apply(true, true);
        // The source bounds fast path only skips work; the same mergers decide occupancy.
        var mergers = merge(first, second, op);
        for (int x = 0; x < mergers[0].first.length; x++) for (int y = 0; y < mergers[1].first.length; y++) for (int z = 0; z < mergers[2].first.length; z++) {
            if (op.apply(first.full(mergers[0].first[x], mergers[1].first[y], mergers[2].first[z]),
                    second.full(mergers[0].second[x], mergers[1].second[y], mergers[2].second[z]))) return true;
        }
        return false;
    }

    private static IndexMerge[] merge(VoxelShape first, VoxelShape second, BooleanOp op) {
        boolean a = op.apply(true, false), b = op.apply(false, true);
        var x = IndexMerge.create(1, first, second, 0, a, b);
        var y = IndexMerge.create(x.first.length, first, second, 1, a, b);
        var z = IndexMerge.create(x.first.length * y.first.length, first, second, 2, a, b);
        return new IndexMerge[]{x, y, z};
    }
}
