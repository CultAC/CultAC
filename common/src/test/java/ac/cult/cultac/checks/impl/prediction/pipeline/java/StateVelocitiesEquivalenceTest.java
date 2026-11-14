package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StateVelocitiesEquivalenceTest {
    private static final double[] COMPONENTS = {0.0, -0.0, 0.1, -0.0784000015258789, Double.NaN, 1.0E-9};

    @Test
    void commitsTheVelocitiesALinkedSetWouldHold() {
        Random random = new Random(7);
        for (int run = 0; run < 20_000; run++) {
            List<JavaPredictionCarry.State> states = new ArrayList<>();
            int count = random.nextInt(9);
            for (int i = 0; i < count; i++) {
                Vec3 velocity = random.nextInt(4) == 0 && !states.isEmpty()
                        ? states.get(random.nextInt(states.size())).velocity()
                        : new Vec3(component(random), component(random), component(random));
                var state = new JavaPredictionCarry.State(velocity, random.nextInt(3), null);
                if (!states.contains(state)) states.add(state);
            }
            List<JavaPredictionCarry.State> list = List.copyOf(states);
            Set<Vec3> linked = new LinkedHashSet<>(list.size() * 2);
            for (var state : list) linked.add(state.velocity());

            Set<Vec3> view = new JavaMovementEngine.StateVelocities(list);
            assertEquals(linked.size(), view.size(), "run " + run);
            assertEquals(linked.isEmpty(), view.isEmpty(), "run " + run);
            assertSameOrder(List.copyOf(linked), new ArrayList<>(view), run);

            Set<Vec3> expected = new PredictionCommit(null, linked).startingVelocities();
            Set<Vec3> actual = new PredictionCommit(null, view).startingVelocities();
            assertEquals(expected.getClass(), actual.getClass(), "run " + run);
            assertSameOrder(new ArrayList<>(expected), new ArrayList<>(actual), run);
        }
    }

    private static double component(Random random) {
        return COMPONENTS[random.nextInt(COMPONENTS.length)];
    }

    private static void assertSameOrder(List<Vec3> expected, List<Vec3> actual, int run) {
        assertEquals(expected.size(), actual.size(), "run " + run);
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i), "run " + run + " at " + i);
    }
}
