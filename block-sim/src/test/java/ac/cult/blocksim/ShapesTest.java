package ac.cult.blocksim;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.SupportType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShapesTest {
    @Test void epsilonSnappingIsConditionalOnAllThreeAxesBeingDiscrete() {
        var snapped = Shapes.create(0.125 + 0.5E-7, 0, 0, 0.5, 1, 1);
        assertEquals(List.of(new Box(0.125, 0, 0, 0.5, 1, 1)), snapped.boxes());
        var unsnapped = Shapes.create(0.125 + 0.5E-7, 0.123, 0, 0.5, 1, 1);
        assertEquals(0.125 + 0.5E-7, unsnapped.boxes().getFirst().minX());
        assertTrue(Shapes.create(0, 0, 0, 0.5E-7, 1, 1).isEmpty());
    }

    @Test void supportUsesTheRequestedFaceAndExactSupportMask() {
        var slab = Shapes.create(0, 0, 0, 1, 0.5, 1);
        assertTrue(SupportType.FULL.supports(slab, Direction.DOWN));
        assertFalse(SupportType.FULL.supports(slab, Direction.UP));
        assertFalse(SupportType.FULL.supports(slab, Direction.NORTH));
        assertTrue(SupportType.CENTER.supports(Shapes.block(), Direction.NORTH));
        assertTrue(SupportType.RIGID.supports(Shapes.block(), Direction.UP));
        var column = Shapes.create(7.0 / 16, 0, 7.0 / 16, 9.0 / 16, 1, 9.0 / 16);
        assertTrue(SupportType.CENTER.supports(column, Direction.UP));
        assertFalse(SupportType.RIGID.supports(column, Direction.UP));
    }

    @Test void mergedBoxesPreserveOccupiedCellsAcrossSubtractAndMove() {
        var hole = Shapes.create(0.25, 0, 0.25, 0.75, 1, 0.75);
        var ring = Shapes.join(Shapes.block(), hole, BooleanOp.ONLY_FIRST);
        assertFalse(Shapes.joinIsNotEmpty(ring, hole, BooleanOp.AND));
        assertFalse(Shapes.joinIsNotEmpty(Shapes.block(), Shapes.join(ring, hole, BooleanOp.OR), BooleanOp.NOT_SAME));
        assertEquals(4, ring.boxes().size());
        assertEquals(ring.boxes().stream().map(b -> b.move(3, -2, 1)).toList(), ring.move(3, -2, 1).boxes());
    }
}
