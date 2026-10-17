package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WireIdTest {
    @Test
    void pingIdsAreNotSequential() {
        SecureRandom random = new SecureRandom();
        Set<Integer> seen = new HashSet<>();
        int steps = 0;
        int previous = CultPlayer.nextWireId(random);
        for (int i = 0; i < 1000; i++) {
            int id = CultPlayer.nextWireId(random);
            if (Math.abs((long) id - previous) <= 1) steps++;
            seen.add(id);
            previous = id;
        }
        assertTrue(steps < 3, "ids stepped by one " + steps + " times");
        assertTrue(seen.size() > 990, "only " + seen.size() + " distinct ids");
    }

    @Test
    void pingIdsStayOutsideTheSignedShortRange() {
        int[] script = {0, -1, Short.MAX_VALUE, Short.MIN_VALUE, -12345, Short.MAX_VALUE + 1};
        SecureRandom scripted = new SecureRandom() {
            private int next;

            @Override
            public int nextInt() {
                return script[next++];
            }
        };
        assertEquals(Short.MAX_VALUE + 1, CultPlayer.nextWireId(scripted));

        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 100_000; i++) {
            int id = CultPlayer.nextWireId(random);
            assertTrue(id < Short.MIN_VALUE || id > Short.MAX_VALUE, "id " + id);
        }
    }
}
