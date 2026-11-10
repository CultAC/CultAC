package ac.cult.cultac.checks.impl.prediction.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The slime block launches follow every candidate present when they start, in order, each launch direction after
 * the candidate it launches, exactly as a walk over a copy of the candidates taken first.
 */
class SlimeLaunchCandidatesTest {
    private static List<PredVector> reference(List<PredVector> start, Set<Direction> launches) {
        for (PredVector vector : new ArrayList<>(start)) {
            for (Direction direction : launches) {
                double x = direction.getModX() == 0 ? vector.getX() : direction.getModX();
                double y = direction.getModY() == 0 ? vector.getY() : direction.getModY();
                double z = direction.getModZ() == 0 ? vector.getZ() : direction.getModZ();
                start.add(vector.withXYZ(x, y, z, MovementModifiers.SLIME_BLOCK_LAUNCH));
            }
        }
        return start;
    }

    private static void assertSameCandidates(List<PredVector> expected, List<PredVector> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            PredVector e = expected.get(i), a = actual.get(i);
            assertEquals(Double.doubleToRawLongBits(e.x), Double.doubleToRawLongBits(a.x), "x " + i);
            assertEquals(Double.doubleToRawLongBits(e.y), Double.doubleToRawLongBits(a.y), "y " + i);
            assertEquals(Double.doubleToRawLongBits(e.z), Double.doubleToRawLongBits(a.z), "z " + i);
            assertSame(lastVector(e), lastVector(a), "source " + i);
            assertEquals(e.hasReason(MovementModifiers.SLIME_BLOCK_LAUNCH), a.hasReason(MovementModifiers.SLIME_BLOCK_LAUNCH));
        }
    }

    private static Object lastVector(PredVector vector) {
        try {
            var field = PredVector.class.getDeclaredField("lastVector");
            field.setAccessible(true);
            return field.get(vector);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void launchesFollowTheStartingCandidates() throws Exception {
        Method launch = MovementModifiers.class.getDeclaredMethod(
                "addSlimePistonLaunches", List.class, SimulationContext.class);
        launch.setAccessible(true);
        Random random = new Random(5);
        Direction[] directions = Direction.values();
        int launched = 0;
        for (int round = 0; round < 2000; round++) {
            Set<Direction> launches = EnumSet.noneOf(Direction.class);
            int count = random.nextInt(4);
            for (int i = 0; i < count; i++) launches.add(directions[random.nextInt(directions.length)]);
            SimulationContext context = mock(SimulationContext.class, RETURNS_DEEP_STUBS);
            when(context.getWorldData().getPistonPushes().getSlimeBlockLaunches()).thenReturn(launches);
            List<PredVector> base = new ArrayList<>();
            int size = random.nextInt(6);
            for (int i = 0; i < size; i++)
                base.add(new PredVector(new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())));
            List<PredVector> actual = new ArrayList<>(base);
            launch.invoke(null, actual, context);
            assertSameCandidates(reference(new ArrayList<>(base), launches), actual);
            launched += actual.size() - base.size();
        }
        assertTrue(launched > 1000, "launched=" + launched);
    }
}
