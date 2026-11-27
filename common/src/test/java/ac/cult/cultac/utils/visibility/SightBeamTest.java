package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SightBeamTest {
    @Test
    void aBoxBetweenTheEyesAndTheTargetIsKeptAndOneBesideThemIsNot() {
        SightBeam beam =
                new SightBeam(List.of(new Vec3(0.2, 1.6, 0.5), new Vec3(0.8, 1.6, 0.5)), box(0, 1, 6, 1, 2, 7));
        assertTrue(meets(beam, box(0, 1, 3, 1, 2, 4)));
        assertFalse(meets(beam, box(3, 1, 3, 4, 2, 4)));
        assertFalse(meets(beam, box(0, 3, 3, 1, 4, 4)));
        assertFalse(meets(beam, box(0, 1, 8, 1, 2, 9)));
    }

    @Test
    void noBoxASightLineCrossesIsDropped() {
        Random random = new Random(7);
        for (int round = 0; round < 2000; round++) {
            Vec3 from = new Vec3(random.nextDouble() * 4, random.nextDouble() * 4, random.nextDouble() * 4);
            Vec3 to = from.add(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5);
            double x = random.nextInt(12) - 6, y = random.nextInt(12) - 6, z = random.nextInt(12) - 6;
            SimpleCollisionBox target = box(x, y, z, x + 1, y + 1, z + 1);
            SightBeam beam = new SightBeam(List.of(from, to), target);
            // A point on a line from an eye to the target, and a block around it.
            Vec3 eye = random.nextBoolean() ? from : to;
            Vec3 end = new Vec3(x + random.nextDouble(), y + random.nextDouble(), z + random.nextDouble());
            double along = random.nextDouble();
            Vec3 on = eye.add(end.subtract(eye).scale(along));
            double bx = Math.floor(on.x), by = Math.floor(on.y), bz = Math.floor(on.z);
            assertTrue(meets(beam, box(bx, by, bz, bx + 1, by + 1, bz + 1)), "round " + round);
        }
    }

    @Test
    void boxesSharingAWholeFaceAreJoined() {
        assertEquals(
                1,
                SightBeam.merge(List.of(box(0, 0, 0, 1, 1, 1), box(1, 0, 0, 2, 1, 1)), 64)
                        .size());
        // A row of three joins whatever the order, and so do two rows side by side.
        List<SimpleCollisionBox> rows = SightBeam.merge(
                List.of(
                        box(2, 0, 0, 3, 1, 1),
                        box(0, 0, 0, 1, 1, 1),
                        box(0, 0, 1, 1, 1, 2),
                        box(1, 0, 0, 2, 1, 1),
                        box(2, 0, 1, 3, 1, 2),
                        box(1, 0, 1, 2, 1, 2)),
                64);
        assertEquals(1, rows.size());
        assertBox(rows.get(0), 0, 0, 0, 3, 1, 2);
        // An L does not make one box, nor do boxes that only share part of a face.
        assertEquals(
                2,
                SightBeam.merge(List.of(box(0, 0, 0, 2, 1, 1), box(0, 1, 0, 1, 2, 1)), 64)
                        .size());
        assertEquals(
                2,
                SightBeam.merge(List.of(box(0, 0, 0, 1, 1, 1), box(1, 0, 0, 2, 0.5, 1)), 64)
                        .size());
    }

    @Test
    void theLargestBoxesAreKeptPastTheLimit() {
        List<SimpleCollisionBox> kept = SightBeam.merge(
                List.of(box(0, 0, 0, 1, 1, 1), box(5, 0, 0, 8, 3, 3), box(10, 0, 0, 10.5, 0.5, 0.5)), 2);
        assertEquals(2, kept.size());
        assertBox(kept.get(0), 5, 0, 0, 8, 3, 3);
        assertBox(kept.get(1), 0, 0, 0, 1, 1, 1);
    }

    private static boolean meets(SightBeam beam, SimpleCollisionBox b) {
        return beam.meets(2, b.minX, b.minY, b.maxX, b.maxY)
                && beam.meets(0, b.minY, b.minZ, b.maxY, b.maxZ)
                && beam.meets(1, b.minZ, b.minX, b.maxZ, b.maxX);
    }

    private static void assertBox(
            SimpleCollisionBox b, double x0, double y0, double z0, double x1, double y1, double z1) {
        assertArrayEquals(
                new double[] {x0, y0, z0, x1, y1, z1}, new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ});
    }

    private static SimpleCollisionBox box(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
