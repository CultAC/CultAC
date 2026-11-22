package ac.cult.blocksim;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AabbVoxelIntersectionTest {
    @Test
    void directOccupancyMatchesVanillaMergersForGeneratedGridsAndBoundaryQueries() {
        var registry = DataTables.defaults().registry();
        var seen = Collections.newSetFromMap(new IdentityHashMap<VoxelShape, Boolean>());
        var random = new Random(853971);
        double eps = Shapes.EPSILON;
        double[][] offsets = {{0, 0, 0}, {4, 64, 4}, {-17, -64, 23}, {30_000_000, 256, -30_000_000}, {.013, -.021, .037}};
        double[] widths = {eps / 2, eps, Math.nextUp(eps), .2, 1.8};
        for (int state = 0; state < registry.stateCount(); state++) {
            var catalog = registry.shapes();
            for (var root : List.of(catalog.collision(state), catalog.outline(state),
                    catalog.support(state), catalog.interaction(state))) {
                if (!seen.add(root) || root.isEmpty()) continue;
                for (var offset : offsets) {
                    var moved = root.move(offset[0], offset[1], offset[2]);
                    for (int trial = 0; trial < 32; trial++) {
                        double[] lo = new double[3], hi = new double[3];
                        for (int axis = 0; axis < 3; axis++) {
                            double face = root.coordinateAt(axis, random.nextInt(root.coordinateCount(axis))) + offset[axis];
                            double delta = (trial % 7 - 3) * eps / 2;
                            double width = widths[random.nextInt(widths.length)];
                            if ((trial & 1) == 0) { lo[axis] = face + delta; hi[axis] = lo[axis] + width; }
                            else { hi[axis] = face + delta; lo[axis] = hi[axis] - width; }
                        }
                        var query = Shapes.create(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
                        boolean expected = Shapes.joinIsNotEmpty(moved, query, BooleanOp.AND);
                        boolean actual = root.intersectsAabb(query, offset[0], offset[1], offset[2], true);
                        if (expected != actual) fail("state=" + state + " offset=" + Arrays.toString(offset)
                                + " lo=" + Arrays.toString(lo) + " hi=" + Arrays.toString(hi));
                    }
                }
            }
        }
    }

    @Test
    void subEpsilonGridIntervalsAndSnappedQueriesRetainIndexMergeSemantics() {
        var cells = new BitSet(); cells.set(0); cells.set(2);
        var shape = Shapes.fromGrid(new double[][]{{0, 5e-8, .5, 1}, {0, 1}, {0, 1}}, false, cells);
        for (var query : List.of(Shapes.block(), Shapes.create(0, 0, 0, 5e-8, 1, 1),
                Shapes.create(.125 + 1e-8, 0, 0, .5, 1, 1))) {
            assertEquals(Shapes.joinIsNotEmpty(shape, query, BooleanOp.AND),
                    shape.intersectsAabb(query, 0, 0, 0, false));
        }
    }
}
