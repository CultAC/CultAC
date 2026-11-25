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

class ClientShapeBoxesTest {
    @Test
    void directCachedComparisonRetainsOrderedNormalizedComparison() {
        var registry = DataTables.defaults().registry();
        var random = new Random(1680527);
        for (int i = 0; i < 10_000; i++) {
            var first = registry.shapes().collision(random.nextInt(registry.stateCount()));
            var second = registry.shapes().collision(random.nextInt(registry.stateCount()));
            assertEquals(
                    ClientShapeBoxes.comparison(first).matches(second),
                    ClientShapeBoxes.sameCachedShape(first, second));
        }
    }

    @Test
    void fixedIndexReusesGeneratedListsAndRetainsExactOrder() {
        var registry = DataTables.defaults().registry();
        var existing = Collections.newSetFromMap(new IdentityHashMap<List<Box>, Boolean>());
        for (int state = 0; state < registry.stateCount(); state++) {
            var facts = registry.facts(state);
            existing.add(facts.collision());
            existing.add(facts.outline());
            existing.add(facts.support());
            existing.add(facts.interaction());
        }
        for (int state = 0; state < registry.stateCount(); state++) {
            var shapes = registry.shapes();
            for (var shape : new VoxelShape[] {
                shapes.collision(state), shapes.outline(state), shapes.support(state), shapes.interaction(state)
            }) {
                var boxes = ClientShapeBoxes.cached(shape);
                assertNotNull(boxes);
                assertTrue(existing.contains(boxes));
                assertEquals(shape.boxes(), boxes);
            }
        }
        assertNull(ClientShapeBoxes.cached(Shapes.block().move(2, 3, 4)));
    }

    @Test
    void comparisonsPreserveNormalizationOrderAndValidateAfterMismatch() {
        var shape = Shapes.create(-0.0, 0, 0, 0.3, 0.5, 0.7);
        assertTrue(ClientShapeBoxes.comparison(shape).matches(shape.move(-0.0, -0.0, -0.0)));
        assertFalse(ClientShapeBoxes.comparison(shape).matches(shape.move(1, 0, 0)));
        assertFalse(ClientShapeBoxes.comparison(Shapes.empty()).matches(shape));
        assertFalse(ClientShapeBoxes.comparison(shape).matches(Shapes.empty()));
        var infinite =
                Shapes.fromBoxes(List.of(new Box(0, 0, 0, 1, 1, 1), new Box(2, 0, 0, Double.POSITIVE_INFINITY, 1, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientShapeBoxes.comparison(Shapes.empty())
                        .matches(infinite.move(Double.NEGATIVE_INFINITY, 0, 0)));
    }

    @Test
    void convertedBoxesStayIndependentAndVoxelCopiesUseShapeBounds() {
        var shape = Shapes.block();
        var first = NativeBlockCollisionHelper.fromShape(shape, 2, 3, 4);
        var second = NativeBlockCollisionHelper.fromShape(shape, 2, 3, 4);
        first.offset(20, 0, 0);
        var boxes = new ArrayList<SimpleCollisionBox>();
        second.downCast(boxes);
        assertEquals(2, boxes.getFirst().minX);
        var voxel = new VoxelShapeCollisionBox(shape, true).offset(2, 3, 4);
        voxel.expand(10, 10, 10);
        var copy = voxel.copy();
        assertEquals(2, copy.minX);
        assertEquals(3, copy.minY);
        assertEquals(4, copy.minZ);
        assertTrue(copy.isFullBlock());
    }

    @Test
    void scalarOverlapMatchesMaterializedPartsAtTouchingAndEpsilonBoundaries() {
        var shape = Shapes.fromBoxes(List.of(new Box(-0.0, 0, 0, .3, .5, .7), new Box(.6, .2, .1, 1, 1, 1)));
        var voxel = new VoxelShapeCollisionBox(shape).offset(.1, -.25, .3);
        var parts = new ArrayList<SimpleCollisionBox>();
        voxel.downCast(parts);
        for (double x : new double[] {0, .1, .4, Math.nextDown(.4), .4 - 1e-7, .4 + 1e-7, .7, 1.1}) {
            var query = SimpleCollisionBox.between(x, -.25, .3, x + .1, .25, 1);
            assertEquals(
                    parts.stream().anyMatch(part -> part.isCollided(query)),
                    new VoxelShapeCollisionBox(shape).offset(.1, -.25, .3).isCollided(query));
            assertEquals(
                    parts.stream().anyMatch(part -> part.isIntersected(query)),
                    new VoxelShapeCollisionBox(shape).offset(.1, -.25, .3).isIntersected(query));
        }
    }
}
