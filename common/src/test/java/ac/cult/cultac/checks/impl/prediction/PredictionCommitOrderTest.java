package ac.cult.cultac.checks.impl.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReusableVelocitySet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PredictionCommitOrderTest {
    // Distinct vectors with equal hashCode (1 * 31 == 31) share a HashSet bucket, so insertion order decides iteration.
    private static final Vec3 FIRST = new Vec3(0, Double.longBitsToDouble(1L), 0);
    private static final Vec3 SECOND = new Vec3(0, 0, Double.longBitsToDouble(31L));

    @Test
    void startingVelocitiesIterateInGenerationOrder() {
        List<Vec3> generated = new ArrayList<>();
        for (int i = 0; i < 12; i++) generated.add(new Vec3(i * 0.1, -0.0784 * i, 12 - i));
        var forward = new PredictionCommit(null, new LinkedHashSet<>(generated));
        assertEquals(generated, List.copyOf(forward.startingVelocities()));
        List<Vec3> reversed = generated.reversed();
        var backward = new PredictionCommit(null, new LinkedHashSet<>(reversed));
        assertEquals(reversed, List.copyOf(backward.startingVelocities()));
        assertEquals(forward, backward);
    }

    @Test
    void hashSetInputIteratesInItsOwnDeterministicOrder() {
        var input = new HashSet<>(List.of(new Vec3(1, 2, 3), new Vec3(-1, 0.5, 0), Vec3.ZERO, new Vec3(0.2, 0, 0)));
        assertEquals(List.copyOf(input), List.copyOf(new PredictionCommit(null, input).startingVelocities()));
    }

    @Test
    void equalHashCandidatesReachTheNextTickSetInGenerationOrder() {
        assertEquals(FIRST.hashCode(), SECOND.hashCode());
        for (List<Vec3> generated : List.of(List.of(FIRST, SECOND), List.of(SECOND, FIRST))) {
            var commit = new PredictionCommit(null, new LinkedHashSet<>(generated));
            // SimulationProcessor#applyProfileCommit: construct, or refill the retained set in place.
            assertEquals(generated, List.copyOf(new ReusableVelocitySet(commit.startingVelocities())));
            var retained = new ReusableVelocitySet(Set.of(new Vec3(5, 5, 5)));
            assertTrue(retained.refill(commit.startingVelocities()));
            assertEquals(generated, List.copyOf(retained));
            assertEquals(generated, List.copyOf(new HashSet<>(commit.startingVelocities())));
        }
    }

    @Test
    void contractIsUnchanged() {
        assertEquals(Set.of(), new PredictionCommit(null, null).startingVelocities());
        var single = new PredictionCommit(null, new HashSet<>(List.of(FIRST))).startingVelocities();
        assertEquals(Set.of(FIRST), single);
        assertThrows(UnsupportedOperationException.class, () -> single.add(SECOND));
        assertThrows(
                NullPointerException.class,
                () -> new PredictionCommit(null, new HashSet<>(Arrays.asList((Vec3) null))));
        var commit = new PredictionCommit(null, new LinkedHashSet<>(List.of(FIRST, SECOND)));
        assertEquals(Set.of(FIRST, SECOND), commit.startingVelocities());
        assertThrows(UnsupportedOperationException.class, () -> commit.startingVelocities().add(Vec3.ZERO));
        assertThrows(
                NullPointerException.class,
                () -> new PredictionCommit(null, new HashSet<>(Arrays.asList(FIRST, null))));
    }
}
