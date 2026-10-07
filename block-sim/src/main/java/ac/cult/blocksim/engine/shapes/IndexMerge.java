package ac.cult.blocksim.engine.shapes;

import java.util.Arrays;

/** Vanilla index mergers, retaining the coordinate-list type needed for cube-grid joins. */
final class IndexMerge {
    final double[] coords;
    final int[] first, second;
    final boolean discrete, cube;

    private IndexMerge(double[] coords, int[] first, int[] second, boolean discrete, boolean cube) {
        this.coords = coords; this.first = first; this.second = second; this.discrete = discrete; this.cube = cube;
    }

    static IndexMerge create(int cost, VoxelShape a, VoxelShape b, int axis, boolean firstOnly, boolean secondOnly) {
        double[] first = a.coords[axis], second = b.coords[axis];
        int as = first.length - 1, bs = second.length - 1;
        // Shapes.createIndexMerger: CubePointRange uses a common discrete grid under cost 256.
        if (a.cubeCoords[axis] && b.cubeCoords[axis]) {
            int gcd = gcd(as, bs); long size = (long) as * (bs / gcd);
            if ((long) cost * size <= 256L) {
                int count = (int) size; var ai = new int[count]; var bi = new int[count];
                for (int i = 0; i < count; i++) { ai[i] = i / (bs / gcd); bi[i] = i / (as / gcd); }
                return new IndexMerge(Shapes.cubeCoords(count), ai, bi, true, true);
            }
        }
        if (first[as] < second[0] - Shapes.EPSILON) return separated(first, second, false);
        if (second[bs] < first[0] - Shapes.EPSILON) return separated(second, first, true);
        if (Arrays.equals(first, second)) {
            var ai = new int[as]; for (int i = 0; i < as; i++) ai[i] = i;
            return new IndexMerge(first, ai, ai, false, a.cubeCoords[axis]);
        }
        // IndirectMerger constructor: ordering and endpoint coalescing deliberately differ.
        double last = Double.NaN;
        int capacity = first.length + second.length, count = 0, ai = 0, bi = 0;
        var result = new double[capacity]; var ais = new int[capacity]; var bis = new int[capacity];
        while (ai < first.length || bi < second.length) {
            boolean aOut = ai >= first.length, bOut = bi >= second.length;
            boolean chooseA = !aOut && (bOut || first[ai] < second[bi] + Shapes.EPSILON);
            if (chooseA) {
                ai++;
                if (!firstOnly && (bi == 0 || bOut)) continue;
            } else {
                bi++;
                if (!secondOnly && (ai == 0 || aOut)) continue;
            }
            double next = chooseA ? first[ai - 1] : second[bi - 1];
            if (!(last >= next - Shapes.EPSILON)) {
                ais[count] = ai - 1; bis[count] = bi - 1; result[count++] = next; last = next;
            } else { ais[count - 1] = ai - 1; bis[count - 1] = bi - 1; }
        }
        int length = Math.max(1, count);
        if (length == 1) result[0] = 0.0;
        return new IndexMerge(Arrays.copyOf(result, length), Arrays.copyOf(ais, length - 1), Arrays.copyOf(bis, length - 1), false, false);
    }

    private static IndexMerge separated(double[] lower, double[] upper, boolean swap) {
        int count = lower.length + upper.length - 1;
        var coords = Arrays.copyOf(lower, count + 1); System.arraycopy(upper, 0, coords, lower.length, upper.length);
        var first = new int[count]; var second = new int[count];
        for (int i = 0; i < lower.length; i++) { first[i] = i; second[i] = -1; }
        for (int i = 0; i < upper.length - 1; i++) { first[lower.length + i] = lower.length - 1; second[lower.length + i] = i; }
        return new IndexMerge(coords, swap ? second : first, swap ? first : second, false, false);
    }

    private static int gcd(int first, int second) {
        while (second != 0) { int remainder = first % second; first = second; second = remainder; }
        return first;
    }
}
