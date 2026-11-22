package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TranslatedVoxelCollisionTest {
    @Test
    void nonFiniteTranslationsRetainClippingAndEagerCopyValidation() {
        var actor = SimpleCollisionBox.between(-.6, .1, .1, -.1, .9, .9);
        var view = new VoxelShapeCollisionBox(Shapes.block()).offset(0, Double.NaN, 0);
        assertEquals(
                Shapes.block().move(0, Double.NaN, 0).collide(0, -.6, .1, .1, -.1, .9, .9, .2),
                view.collideX(actor, .2, SimpleCollisionBox.AxisEpsilon.JAVA));
        assertThrows(IllegalArgumentException.class, view::copy);
    }

    @Test
    void sharedGridClippingMatchesMaterializedTranslationAtCellAndEpsilonBoundaries() {
        var seen = Collections.newSetFromMap(new IdentityHashMap<VoxelShape, Boolean>());
        var registry = DataTables.defaults().registry();
        var random = new Random(741852963);
        double[][] offsets = {{0, 64, 0}, {-17, -64, 23}, {30_000_000, 256, -30_000_000}, {.125, -.25, .5}};
        double eps = Shapes.EPSILON;
        double[] distances = {
            -1.1, -.2, -eps, -Math.nextDown(eps), -eps / 2, -0.0, 0.0, eps / 2, Math.nextDown(eps), eps, .2, 1.1
        };
        for (int state = 0; state < registry.stateCount(); state++) {
            var catalog = registry.shapes();
            for (var original : List.of(
                    catalog.collision(state),
                    catalog.outline(state),
                    catalog.support(state),
                    catalog.interaction(state))) {
                if (!seen.add(original) || original.isEmpty()) continue;
                for (var offset : offsets) {
                    var moved = original.move(offset[0], offset[1], offset[2]);
                    var view = new VoxelShapeCollisionBox(original).offset(offset[0], offset[1], offset[2]);
                    for (int trial = 0; trial < 16; trial++) {
                        double[] lo = new double[3], hi = new double[3];
                        for (int axis = 0; axis < 3; axis++) {
                            double face = original.coordinateAt(axis, random.nextInt(original.coordinateCount(axis)))
                                    + offset[axis];
                            double delta = (trial % 5 - 2) * eps;
                            double width = axis == 1 ? 1.8 : .6;
                            if ((trial & 1) == 0) {
                                lo[axis] = face + delta;
                                hi[axis] = lo[axis] + width;
                            } else {
                                hi[axis] = face + delta;
                                lo[axis] = hi[axis] - width;
                            }
                        }
                        var box = new Box(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
                        var simple = SimpleCollisionBox.between(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
                        for (int axis = 0; axis < 3; axis++)
                            for (double distance : distances) {
                                double expected = moved.collide(axis, box, distance);
                                double actual = switch (axis) {
                                    case 0 -> view.collideX(simple, distance, SimpleCollisionBox.AxisEpsilon.JAVA);
                                    case 1 -> view.collideY(simple, distance, SimpleCollisionBox.AxisEpsilon.JAVA);
                                    default -> view.collideZ(simple, distance, SimpleCollisionBox.AxisEpsilon.JAVA);
                                };
                                if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual))
                                    fail("axis=" + axis + " expected=" + expected + " actual=" + actual);
                            }
                    }
                }
            }
        }
    }

    @Test
    void repeatedOffsetsCopiesAndStepCoordinatesPreserveSequentialAdditions() {
        var root = Shapes.create(.125, -.25, .5, .75, .875, 1.125);
        var expected = root.move(30_000_000, 256, -30_000_000).move(.013, -.021, .037);
        var view = new VoxelShapeCollisionBox(root)
                .offset(30_000_000, 256, -30_000_000)
                .offset(.013, -.021, .037);
        var copy = view.copy();
        for (int i = 0; i < expected.coordinateCount(1); i++) {
            assertEquals(expected.coordinateAt(1, i), copy.stepCoordinateAt(i), 0);
        }
        assertArrayEquals(expected.coordinates(1), copy.stepCoordinates());
        assertTrue(new VoxelShapeCollisionBox(expected).sameGrid(copy));
        view.offset(1, 2, 3);
        assertTrue(expected.sameGrid(copy.voxelShape()));
        var boxes = new ArrayList<SimpleCollisionBox>();
        copy.downCast(boxes);
        assertEquals(expected.boxes().getFirst().minX(), boxes.getFirst().minX, 0);
    }
}
