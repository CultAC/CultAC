package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** SneakEdgeBackoff's run arithmetic lands bit for bit where Player#maybeBackOffFromEdge's repeated steps do. */
class SneakEdgeBackoffStepsTest {
    @Test
    void runsMatchRepeatedSubtraction() {
        Random random = new Random(3);
        List<Double> starts = new ArrayList<>();
        for (double edge : new double[] {0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 1024.0, 65536.0}) {
            double value = edge;
            for (int i = 0; i < 4; i++) {
                starts.add(value);
                starts.add(Math.nextUp(value) + 0.05 * i);
                starts.add(Math.nextDown(value) + 0.05 * i);
                value = Math.nextUp(value);
            }
        }
        // Runs landing exactly on a binade's bottom, where the last step rounds on the finer ulp below.
        for (int exponent = 0; exponent <= 14; exponent++) {
            double low = Math.scalb(1.0, exponent), middle = low * 1.5;
            double removed = middle - (middle - 0.05);
            for (int k : new int[] {1, 2, 3, 7, 40}) starts.add(low + k * removed);
        }
        for (int i = 0; i < 400; i++) starts.add(Math.pow(10.0, random.nextDouble() * 5.3 - 1.3));
        starts.add(200_000.123456789);
        for (double start : starts) {
            check(start, random);
            check(-start, random);
        }
    }

    @Test
    void anAbsorbedStepNeverMoves() {
        assertEquals(Long.MAX_VALUE, SneakEdgeBackoff.stepsAbove(1.0E17));
        assertEquals(1.0E17, SneakEdgeBackoff.stepped(1.0E17, 1_000));
        assertEquals(-1.0E17, SneakEdgeBackoff.stepped(-1.0E17, Long.MAX_VALUE));
    }

    private static void check(double start, Random random) {
        double step = Math.signum(start) * 0.05;
        long steps = 0;
        for (double value = start; Math.abs(value) > 0.05; value -= step) steps++;
        assertEquals(steps, SneakEdgeBackoff.stepsAbove(start), "steps from " + start);
        TreeSet<Long> checkpoints = new TreeSet<>();
        if (steps <= 4_000) for (long k = 0; k <= steps; k++) checkpoints.add(k);
        else {
            for (int i = 0; i < 300; i++) checkpoints.add((long) (random.nextDouble() * (steps + 1)));
            checkpoints.add(0L);
            checkpoints.add(steps);
        }
        double value = start;
        long at = 0;
        for (long k : checkpoints) {
            for (; at < k; at++) value -= step;
            assertEquals(
                    Double.doubleToRawLongBits(value),
                    Double.doubleToRawLongBits(SneakEdgeBackoff.stepped(start, k)),
                    start + " after " + k + " steps");
        }
    }
}
