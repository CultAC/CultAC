package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ScalarClipParityTest {
    @Test
    void scalarClipMatchesBoxClipBitForBit() {
        Random random = new Random(0xC11B);
        double[] special = {
            0.0,
            -0.0,
            1e-7,
            -1e-7,
            Math.nextUp(1e-7),
            Math.nextDown(-1e-7),
            1,
            -1,
            .5,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        };
        int hits = 0;
        for (int sample = 0; sample < 200_000; sample++) {
            int x = random.nextInt(7) - 3, y = random.nextInt(7) - 3, z = random.nextInt(7) - 3;
            double toX = x + random.nextDouble() * 3 - 1, toY = y + random.nextDouble() * 3 - 1;
            double toZ = z + random.nextDouble() * 3 - 1;
            double travelX = travel(random, special), travelY = travel(random, special);
            double travelZ = travel(random, special);
            if (sample % 3 == 0) {
                toX = x + random.nextInt(3) - 0.5 * random.nextInt(2);
                toZ = z + (random.nextBoolean() ? 0.0 : 1.0);
            }
            Vec3 to = new Vec3(toX, toY, toZ);
            Vec3 from = new Vec3(toX - travelX, toY - travelY, toZ - travelZ);
            Optional<Vec3> expected = SimpleCollisionBox.clip(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D, from, to);
            double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
            double scale = Collisions.clipScale26Dot2(
                    x, y, z, x + 1.0D, y + 1.0D, z + 1.0D, from.x, from.y, from.z, dx, dy, dz);
            assertEquals(expected.isPresent(), scale >= 0.0D, "sample " + sample);
            if (expected.isEmpty()) continue;
            hits++;
            Vec3 point = expected.get();
            assertEquals(Double.doubleToRawLongBits(point.x), Double.doubleToRawLongBits(from.x + scale * dx));
            assertEquals(Double.doubleToRawLongBits(point.y), Double.doubleToRawLongBits(from.y + scale * dy));
            assertEquals(Double.doubleToRawLongBits(point.z), Double.doubleToRawLongBits(from.z + scale * dz));
        }
        assertTrue(hits > 10_000, "too few hits: " + hits);
    }

    private static double travel(Random random, double[] special) {
        return random.nextInt(4) == 0 ? special[random.nextInt(special.length)] : random.nextDouble() * 4 - 2;
    }
}
