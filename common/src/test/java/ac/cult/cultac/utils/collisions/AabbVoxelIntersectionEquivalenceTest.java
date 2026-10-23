package ac.cult.cultac.utils.collisions;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** VoxelShape.intersectsAabb against the Boolean AND join it shortcuts, concentrated on epsilon ties. */
class AabbVoxelIntersectionEquivalenceTest {
    private static final double EPS = Shapes.EPSILON;
    private static final double[][] OFFSETS = {
        {0, 0, 0}, {4, 64, 4}, {-17, -64, 23}, {-1, -1, -1}, {30_000_000, 256, -30_000_000}, {.013, -.021, .037}
    };
    private static final double[] WIDTHS = {
        0, EPS / 2, Math.nextDown(EPS), EPS, Math.nextUp(EPS), 2 * EPS, Math.nextUp(2 * EPS), 3 * EPS, .2, .6, 1, 1.8
    };
    private static final double[] TIES = {-EPS, EPS, -2 * EPS, 2 * EPS, 0};

    private final Random random = new Random(0x5eed_c011_1de1L);

    @Test
    void translatedBlockShapesMatchTheJoinAtEpsilonTies() {
        int ties = 0;
        for (VoxelShape root : shapes()) {
            for (double[] offset : OFFSETS) {
                VoxelShape moved = root.move(offset[0], offset[1], offset[2]);
                for (int trial = 0; trial < 96; trial++) {
                    double[] lo = new double[3], hi = new double[3];
                    for (int axis = 0; axis < 3; axis++) {
                        double face =
                                root.coordinateAt(axis, random.nextInt(root.coordinateCount(axis))) + offset[axis];
                        double tie = TIES[random.nextInt(TIES.length)];
                        double bound = face + tie;
                        int ulps = random.nextInt(9) - 4;
                        for (int i = 0; i < Math.abs(ulps); i++)
                            bound = ulps > 0 ? Math.nextUp(bound) : Math.nextDown(bound);
                        double width = WIDTHS[random.nextInt(WIDTHS.length)];
                        if (random.nextBoolean()) {
                            lo[axis] = bound;
                            hi[axis] = bound + width;
                        } else {
                            hi[axis] = bound;
                            lo[axis] = bound - width;
                        }
                        if (tie != 0) ties++;
                    }
                    check(root, moved, lo, hi, offset, true);
                }
            }
        }
        assertTrue(ties > 100_000, "tie cases " + ties);
    }

    @Test
    void untranslatedShapesKeepCubeGridAndIdentityMergers() {
        List<VoxelShape> shapes = shapes();
        double[] dyadic = {0, .125, .25, .5, .75, 1};
        for (VoxelShape root : shapes) {
            for (int trial = 0; trial < 24; trial++) {
                double[] lo = new double[3], hi = new double[3];
                for (int axis = 0; axis < 3; axis++) {
                    double a = random.nextInt(3) == 0
                            ? dyadic[random.nextInt(dyadic.length)]
                            : root.coordinateAt(axis, random.nextInt(root.coordinateCount(axis)))
                                    + TIES[random.nextInt(TIES.length)];
                    double width = random.nextBoolean()
                            ? dyadic[random.nextInt(dyadic.length)]
                            : WIDTHS[random.nextInt(WIDTHS.length)];
                    lo[axis] = Math.min(a, a + width);
                    hi[axis] = Math.max(a, a + width);
                }
                check(root, root, lo, hi, OFFSETS[0], false);
                check(root, root.move(0, 0, 0), lo, hi, OFFSETS[0], true);
            }
            assertSame(root, root.intersectsAabb(root, 0, 0, 0, false), "identity");
            assertSame(
                    Shapes.joinIsNotEmpty(root, Shapes.block(), BooleanOp.AND),
                    root.intersectsAabb(Shapes.block(), 0, 0, 0, false),
                    "block");
        }
    }

    @Test
    void degenerateQueriesMatchTheJoin() {
        double[] specials = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0};
        for (VoxelShape root : shapes()) {
            for (double[] offset : OFFSETS) {
                VoxelShape moved = root.move(offset[0], offset[1], offset[2]);
                for (int trial = 0; trial < 6; trial++) {
                    double[] lo = new double[3], hi = new double[3];
                    for (int axis = 0; axis < 3; axis++) {
                        double face =
                                root.coordinateAt(axis, random.nextInt(root.coordinateCount(axis))) + offset[axis];
                        lo[axis] = face - EPS;
                        hi[axis] = face + EPS;
                    }
                    int axis = random.nextInt(3);
                    double special = specials[random.nextInt(specials.length)];
                    if (random.nextBoolean()) lo[axis] = special;
                    else hi[axis] = special;
                    check(root, moved, lo, hi, offset, true);
                }
            }
        }
    }

    private void check(
            VoxelShape root, VoxelShape moved, double[] lo, double[] hi, double[] offset, boolean translated) {
        VoxelShape query = Shapes.create(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        boolean expected = Shapes.joinIsNotEmpty(moved, query, BooleanOp.AND);
        boolean actual = root.intersectsAabb(query, offset[0], offset[1], offset[2], translated);
        boolean raw = root.intersectsAabb(
                lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], offset[0], offset[1], offset[2], translated);
        if (expected != actual || expected != raw)
            fail("shape=" + describe(root) + " offset=" + Arrays.toString(offset) + " translated=" + translated
                    + " lo=" + Arrays.toString(lo) + " hi=" + Arrays.toString(hi) + " expected=" + expected
                    + " raw=" + raw);
    }

    private static void assertSame(boolean expected, boolean actual, String what) {
        if (expected != actual) fail(what + " expected=" + expected);
    }

    private static void assertSame(VoxelShape root, boolean actual, String what) {
        assertSame(Shapes.joinIsNotEmpty(root, root, BooleanOp.AND), actual, what);
    }

    private static String describe(VoxelShape shape) {
        var result = new StringBuilder();
        for (int axis = 0; axis < 3; axis++) {
            result.append('[');
            for (int i = 0; i < shape.coordinateCount(axis); i++)
                result.append(i == 0 ? "" : ",").append(shape.coordinateAt(axis, i));
            result.append(']');
        }
        return result + " boxes=" + shape.boxes();
    }

    /** Every non-empty shape the block registry builds, plus off-grid, tight-grid and over-64-cell grids. */
    private static List<VoxelShape> shapes() {
        var registry = DataTables.defaults().registry();
        var catalog = registry.shapes();
        var seen = Collections.newSetFromMap(new IdentityHashMap<VoxelShape, Boolean>());
        var result = new ArrayList<VoxelShape>();
        for (int state = 0; state < registry.stateCount(); state++) {
            for (VoxelShape shape : List.of(
                    catalog.collision(state),
                    catalog.outline(state),
                    catalog.support(state),
                    catalog.interaction(state))) {
                if (!shape.isEmpty() && seen.add(shape)) result.add(shape);
            }
        }
        assertTrue(result.size() > 100, "catalog shapes " + result.size());
        int catalogShapes = result.size();
        for (int i = 0; i < catalogShapes; i += 7) result.add(result.get(i).move(.21875, -.0625, .40625));
        var cells = new BitSet();
        cells.set(0);
        cells.set(2);
        result.add(Shapes.fromGrid(new double[][] {{0, 5e-8, .5, 1}, {0, 1}, {0, 1}}, false, cells));
        result.add(Shapes.fromGrid(new double[][] {{0, 1}, {0, .5, .5 + 1.5e-7, 1}, {0, 1}}, false, cells));
        double[] fine = new double[70];
        for (int i = 0; i < fine.length; i++) fine[i] = i / 69.0;
        var stripes = new BitSet();
        for (int i = 0; i < 69; i += 2) stripes.set(i);
        result.add(Shapes.fromGrid(new double[][] {fine, {0, 1}, {0, 1}}, false, stripes));
        double[] sixtyFour = new double[65];
        for (int i = 0; i < sixtyFour.length; i++) sixtyFour[i] = i / 64.0;
        var last = new BitSet();
        last.set(63);
        last.set(5);
        result.add(Shapes.fromGrid(new double[][] {{0, 1}, {0, 1}, sixtyFour}, false, last));
        return result;
    }
}
