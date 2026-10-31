package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsideBlockTraversalOrderTest {
    private record Visit(SimpleCollisionBox from, SimpleCollisionBox to, BlockPos block) {}

    /** Entity#move/checkInsideBlocks in 1.21.5..1.21.8 orders the collided vector, before the 1.21.9 change. */
    @Test
    void oldAxisTraversalUsesTheCollidedMovementOrder() {
        SimpleCollisionBox from = new SimpleCollisionBox(0.2, 64, 0.2, 0.8, 65.8, 0.8);
        SimpleCollisionBox to = from.copy().offset(0.7, 0, 1.0);
        List<Visit> visits = visits(from, to, new Vec3(2.0, 0, 1.0));
        assertFalse(visits.isEmpty());
        assertEquals(
                1, visits.getFirst().block().getZ(), "Z is the longer collided axis and visits its new cell first");
    }

    /** Each old Entity.Movement leg supplies its own endpoints to collidedWithShapeMovingFrom. */
    @Test
    void oldAxisTraversalSuppliesTheSegmentRatherThanTheWholeDiagonal() {
        SimpleCollisionBox from = new SimpleCollisionBox(0.2, 64, 0.2, 0.8, 65.8, 0.8);
        SimpleCollisionBox to = from.copy().offset(0.7, 0, 1.0);
        List<Visit> visits = visits(from, to, new Vec3(0.7, 0, 1.0));
        Visit first = visits.getFirst();
        assertEquals(from.minX, first.to().minX);
        assertEquals(to.minZ, first.to().minZ);
        assertEquals(from.minX, first.from().minX);
        assertEquals(from.minZ, first.from().minZ);
    }

    /** Through 1.21.8 a block is marked visited before its precise shape test, even if that test rejects it. */
    @Test
    void oldAxisTraversalDoesNotRetryARejectedBlockOnAnotherLeg() {
        SimpleCollisionBox from = new SimpleCollisionBox(0.2, 64, 0.2, 0.8, 65.8, 0.8);
        SimpleCollisionBox to = from.copy().offset(0.7, 0, 1.0);
        var visits = new HashMap<BlockPos, Integer>();
        Collisions.visitInsideBlocksBefore1Dot21Dot9(
                from, to, new Vec3(0.7, 0, 1.0), false, (start, end, block, precise) -> {
                    visits.merge(block, 1, Integer::sum);
                    return false;
                });
        assertFalse(visits.isEmpty());
        for (int count : visits.values()) assertEquals(1, count);
    }

    private static List<Visit> visits(SimpleCollisionBox from, SimpleCollisionBox to, Vec3 attempted) {
        List<Visit> visits = new ArrayList<>();
        Collisions.visitInsideBlocksBefore1Dot21Dot9(from, to, attempted, false, (start, end, block, precise) -> {
            visits.add(new Visit(start.copy(), end.copy(), block));
            return true;
        });
        return visits;
    }
}
