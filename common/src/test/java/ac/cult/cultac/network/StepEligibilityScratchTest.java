package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The step-eligibility probe's scratch result is read in place and never stored on a prediction. */
class StepEligibilityScratchTest {
    private static final int SCENARIOS = 66;

    @Test
    void theScratchResultIsNeverStoredAndMatchesAFreshProbe() throws Exception {
        Field scratchField = CollisionModifier.class.getDeclaredField("reusableResult");
        scratchField.setAccessible(true);
        Field probeField = SimulationProcessor.class.getDeclaredField("collisionProbe");
        probeField.setAccessible(true);
        List<String> mismatches = new ArrayList<>();
        int[] reused = {0};
        int[] stored = {0};
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            try (var f = new JavaTickFixture()) {
                CollisionModifier fresh = new CollisionModifier();
                CollisionModifier probe = new CollisionModifier() {
                    @Override
                    public CollideAxisData probeCollisions(
                            CultPlayer player,
                            SimulationContext context,
                            double minY,
                            Vec3 target,
                            Vec3 playerPos,
                            SimpleCollisionBox attemptedMovementExtents,
                            ProbeDimensions dimensions,
                            JavaCollisionState actor,
                            boolean followCandidateVerticalMovement,
                            double horizontalInputReach) {
                        CollideAxisData expected = fresh.probeCollisions(
                                player,
                                context,
                                minY,
                                target,
                                playerPos,
                                attemptedMovementExtents,
                                dimensions,
                                actor,
                                followCandidateVerticalMovement,
                                horizontalInputReach);
                        CollideAxisData actual = super.probeCollisions(
                                player,
                                context,
                                minY,
                                target,
                                playerPos,
                                attemptedMovementExtents,
                                dimensions,
                                actor,
                                followCandidateVerticalMovement,
                                horizontalInputReach);
                        if (actual == scratch(scratchField, this)) reused[0]++;
                        String want = PredictionDigestTest.axes(expected);
                        String got = PredictionDigestTest.axes(actual);
                        if (!want.equals(got)) mismatches.add(scenario + ": " + want + " != " + got);
                        return actual;
                    }
                };
                probeField.set(f.player.checkManager.getSimulationProcessor(), probe);
                Object scratch = scratch(scratchField, probe);
                PredictionScenarios.run(f, seed, result -> {
                    if (result == null) return;
                    List<PredictionResult> all = new ArrayList<>(result.getAnyReality());
                    all.add(result);
                    for (PredictionResult candidate : all) {
                        if (candidate.getCollideAxisData() == null) continue;
                        stored[0]++;
                        if (candidate.getCollideAxisData() == scratch) {
                            mismatches.add(scenario + ": a prediction holds the scratch result");
                        }
                    }
                });
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(reused[0] > SCENARIOS && stored[0] > SCENARIOS, reused[0] + " reused, " + stored[0] + " stored");
    }

    private static Object scratch(Field field, CollisionModifier probe) {
        try {
            return field.get(probe);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
