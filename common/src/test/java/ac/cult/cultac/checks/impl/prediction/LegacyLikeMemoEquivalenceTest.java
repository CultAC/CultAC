package ac.cult.cultac.checks.impl.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The memoised legacy-like vector against a fresh computation on every read, across the inputs' changes. */
class LegacyLikeMemoEquivalenceTest {
    @Test
    void everyReadMatchesAFreshComputation() {
        var random = new SplittableRandom(7);
        double[] radius = {0.2};
        SimulationContext context = mock(SimulationContext.class);
        doAnswer(invocation -> radius[0]).when(context).getHorizontalInputRadius(any(), any());
        Vec3 target = new Vec3(0.31, -0.08, -0.17);
        var result = new PredictionResult(null, new PredVector(Vec3.ZERO), context, target, null, List.of(), List.of());
        Vec3 previous = null;
        int reused = 0, rebuilt = 0;
        for (int i = 0; i < 4000; i++) {
            switch (random.nextInt(6)) {
                case 0 -> result.setValidMovements(movements(vector(random)));
                case 1 -> radius[0] = random.nextInt(4) == 0 ? 0.0 : random.nextDouble(0, 0.4);
                case 2 -> radius[0] = random.nextBoolean() ? -0.0 : 0.0;
                case 3 -> result.setProfilePredictionVector(random.nextInt(3) == 0 ? vector(random) : null);
                case 4 -> result.setValidMovements(random.nextInt(4) == 0 ? null : result.getValidMovements());
                default -> {}
            }
            Vec3 closest = result.getAcceptedClosestToTarget();
            Vec3 expected = result.getProfilePredictionVector() != null
                    ? result.getProfilePredictionVector()
                    : UncertaintyHelper.handleCircularInput(new PredVector(closest), target, radius[0]);
            Vec3 actual = result.getLegacyLikePredictionVector();
            assertEquals(expected.getClass(), actual.getClass());
            assertEquals(Double.doubleToRawLongBits(expected.x), Double.doubleToRawLongBits(actual.x));
            assertEquals(Double.doubleToRawLongBits(expected.y), Double.doubleToRawLongBits(actual.y));
            assertEquals(Double.doubleToRawLongBits(expected.z), Double.doubleToRawLongBits(actual.z));
            if (actual == previous) reused++;
            else rebuilt++;
            previous = actual;
        }
        assertTrue(reused > 400 && rebuilt > 400, "reused " + reused + " rebuilt " + rebuilt);
    }

    @Test
    void readsLeaveEqualityAndTextAlone() {
        SimulationContext context = mock(SimulationContext.class);
        doReturn(0.25).when(context).getHorizontalInputRadius(any(), any());
        Vec3 target = new Vec3(0.3, 0, 0.1);
        PredVector start = new PredVector(Vec3.ZERO);
        CultPlayer player = mock(CultPlayer.class);
        doReturn("p").when(player).getName();
        var read = new PredictionResult(player, start, context, target, null, List.of(), List.of());
        var unread = new PredictionResult(player, start, context, target, null, List.of(), List.of());
        ValidMovements movements = movements(new Vec3(0.1, 0, 0));
        read.setValidMovements(movements);
        unread.setValidMovements(movements);
        Vec3 first = read.getLegacyLikePredictionVector();
        assertSame(first, read.getLegacyLikePredictionVector());
        assertEquals(unread, read);
        assertEquals(unread.hashCode(), read.hashCode());
        assertEquals(unread.toString(), read.toString());
    }

    @Test
    void aMissingClosestThrowsOnEveryRead() {
        SimulationContext context = mock(SimulationContext.class);
        doReturn(0.25).when(context).getHorizontalInputRadius(any(), any());
        var result = new PredictionResult(
                null, new PredVector(Vec3.ZERO), context, new Vec3(1, 0, 0), null, List.of(), List.of());
        result.setValidMovements(movements(null));
        assertThrows(NullPointerException.class, result::getLegacyLikePredictionVector);
        assertThrows(NullPointerException.class, result::getLegacyLikePredictionVector);
    }

    private static ValidMovements movements(Vec3 closest) {
        ValidMovements movements = mock(ValidMovements.class);
        doReturn(closest).when(movements).getClosestToTarget();
        return movements;
    }

    private static Vec3 vector(SplittableRandom random) {
        return random.nextInt(5) == 0
                ? new Vec3(0.31, random.nextDouble(-1, 1), -0.17)
                : new Vec3(random.nextDouble(-1, 1), random.nextDouble(-1, 1), random.nextDouble(-1, 1));
    }
}
