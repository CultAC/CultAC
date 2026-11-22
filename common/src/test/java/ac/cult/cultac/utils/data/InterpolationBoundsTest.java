package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.Test;

class InterpolationBoundsTest {
    @Test
    void endpointUnionMatchesEveryDiscreteCandidateBitForBit() {
        Random random = new Random(0xB0A0D5);
        for (int sample = 0; sample < 20000; sample++) {
            var from = randomBox(random);
            var to = randomBox(random);
            int steps = 1 + random.nextInt(20);
            int low = random.nextInt(steps + 1);
            int high = low + random.nextInt(steps - low + 1);
            assertSameBounds(
                    legacy(from, to, steps, low, high),
                    ReachInterpolationData.combinedInterpolatedBounds(from, to, steps, low, high));
        }
    }

    @Test
    void stationaryResizedAndBoundaryBoxesPreserveFlagsAndOwnership() {
        double[] values = {-30000000, -1 - 1e-7, -1, -1 + 1e-7, -0.0, 0, 1e-7, 1, 30000000};
        for (double x : values) {
            var from = new SimpleCollisionBox(x, 64, x, x + 0.6F, 65.8F, x + 0.6F);
            var to = new SimpleCollisionBox(x, 64, x, x + 1.0, 65, x + 1.0);
            for (int low = 0; low <= 3; low++)
                for (int high = low; high <= 3; high++) {
                    var result = ReachInterpolationData.combinedInterpolatedBounds(from, to, 3, low, high);
                    assertSameBounds(legacy(from, to, 3, low, high), result);
                    result.expand(10);
                    assertEquals(x, from.minX);
                    assertEquals(x, to.minX);
                }
        }
        var full = new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
        assertTrue(ReachInterpolationData.combinedInterpolatedBounds(full, full, 3, 0, 3)
                .isFullBlock());
    }

    private static SimpleCollisionBox randomBox(Random random) {
        double x = (random.nextDouble() - 0.5) * 60000000;
        double y = (random.nextDouble() - 0.5) * 4000;
        double z = (random.nextDouble() - 0.5) * 60000000;
        return new SimpleCollisionBox(
                x, y, z, x + random.nextDouble() * 16, y + random.nextDouble() * 16, z + random.nextDouble() * 16);
    }

    private static SimpleCollisionBox legacy(
            SimpleCollisionBox from, SimpleCollisionBox to, int steps, int low, int high) {
        var boxes = new ArrayList<SimpleCollisionBox>();
        for (int i = low; i <= high; i++)
            boxes.add(new SimpleCollisionBox(
                    from.minX + i * ((to.minX - from.minX) / steps),
                    from.minY + i * ((to.minY - from.minY) / steps),
                    from.minZ + i * ((to.minZ - from.minZ) / steps),
                    from.maxX + i * ((to.maxX - from.maxX) / steps),
                    from.maxY + i * ((to.maxY - from.maxY) / steps),
                    from.maxZ + i * ((to.maxZ - from.maxZ) / steps)));
        var result = boxes.getFirst();
        for (int i = 1; i < boxes.size(); i++)
            result = ReachInterpolationData.combineCollisionBox(result, boxes.get(i));
        return result;
    }

    private static void assertSameBounds(SimpleCollisionBox expected, SimpleCollisionBox actual) {
        double[] a = {expected.minX, expected.minY, expected.minZ, expected.maxX, expected.maxY, expected.maxZ};
        double[] b = {actual.minX, actual.minY, actual.minZ, actual.maxX, actual.maxY, actual.maxZ};
        for (int i = 0; i < a.length; i++) assertEquals(Double.doubleToLongBits(a[i]), Double.doubleToLongBits(b[i]));
        assertEquals(expected.isFullBlock(), actual.isFullBlock());
    }
}
