package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.JavaSneakEdgeUnsentHoldTest.Shape;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Whether the engine knows a sneaking hold's deltaMovement tick by tick. A tick the engine replays from CLIENT_TICK_END
 * starts from the committed deltaMovement, so its tick-skip reality does not keep the hold uncarried; a hold the engine
 * could not carry is known again once LivingEntity#aiStep has zeroed the decayed deltaMovement.
 */
class JavaSneakEdgeCarryChainTest {
    @ParameterizedTest
    @EnumSource(
            value = Shape.class,
            names = {"UNCERTAIN_USE_HOLD_IDLE_6", "UNCERTAIN_USE_HOLD_IDLE_10"},
            mode = EnumSource.Mode.EXCLUDE)
    void everyBackedOffTickOfAHoldIsCarried(Shape shape) throws Exception {
        try (var f = new JavaTickFixture()) {
            List<SneakEdgeClient.Tick> ticks = JavaSneakEdgeUnsentHoldTest.hold(f, shape).ticks;
            for (int i = 0; i < ticks.size(); i++) {
                SneakEdgeClient.Tick tick = ticks.get(i);
                assertTrue(tick.result().isSneakEdgeExact(), "tick " + i + " " + describe(tick.result()));
                if (tick.shortened()) {
                    assertTrue(tick.result().isSneakEdgeCarried(), "tick " + i + " " + describe(tick.result()));
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Shape.class,
            names = {"UNCERTAIN_USE_HOLD_IDLE_6", "UNCERTAIN_USE_HOLD_IDLE_10"})
    void aHoldIsCarriedAgainOnceTheIdleZeroedItsDeltaMovement(Shape shape) throws Exception {
        try (var f = new JavaTickFixture()) {
            List<SneakEdgeClient.Tick> ticks = JavaSneakEdgeUnsentHoldTest.hold(f, shape).ticks;
            // Ticks 1 to 4 hold toward the edge under the uncertain use; the last four strafe along it.
            for (int i = 1; i <= 4; i++) {
                PredictionResult result = ticks.get(i).result();
                assertFalse(result.isSneakEdgeCarried(), "tick " + i + " " + describe(result));
                assertFalse(result.isSneakEdgeExact(), "tick " + i + " " + describe(result));
            }
            for (int i = ticks.size() - 4; i < ticks.size(); i++) {
                SneakEdgeClient.Tick tick = ticks.get(i);
                assertTrue(tick.shortened(), "tick " + i);
                assertTrue(tick.result().isSneakEdgeCarried(), "tick " + i + " " + describe(tick.result()));
            }
        }
    }
}
