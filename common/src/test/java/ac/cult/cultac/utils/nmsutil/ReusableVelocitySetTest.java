package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ReusableVelocitySetTest {
    private static List<Vec3> randomVelocities(Random random, int count) {
        List<Vec3> velocities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            velocities.add(new Vec3(
                    random.nextInt(4) * 0.1 - 0.1, random.nextInt(3) * -0.0784, random.nextInt(4) * 0.05 - 0.05));
        }
        return velocities;
    }

    @Test
    void refillIteratesLikeAFreshCopy() {
        Random random = new Random(0x5E7);
        ReusableVelocitySet reused = new ReusableVelocitySet();
        for (int round = 0; round < 20000; round++) {
            HashSet<Vec3> source = new HashSet<>(randomVelocities(random, random.nextInt(18)));
            if (!reused.refill(source)) reused = new ReusableVelocitySet(source);
            assertEquals(new ArrayList<>(new HashSet<>(source)), new ArrayList<>(reused));
        }
    }

    @Test
    void recycledIncrementalUseIteratesLikeAFreshSet() {
        Random random = new Random(0xACE);
        ReusableVelocitySet reused = new ReusableVelocitySet();
        for (int round = 0; round < 20000; round++) {
            List<Vec3> added = randomVelocities(random, random.nextInt(20));
            HashSet<Vec3> fresh = new HashSet<>();
            for (Vec3 velocity : added) {
                fresh.add(velocity);
                reused.add(velocity);
            }
            assertEquals(new ArrayList<>(fresh), new ArrayList<>(reused));
            if (reused.reusable()) reused.clear();
            else reused = new ReusableVelocitySet();
        }
    }
}
