package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.checks.ReportedKeyInputComparison;
import ac.cult.cultac.utils.data.KnownInput;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The scratch-backed key input offset equals, bit for bit, the offset as of ca9de4b40 on every seeded candidate. */
class ReportedKeyInputEquivalenceTest {
    private static final int SCENARIOS = 66;
    private static final int KEY_SETS = 4;

    @Test
    void everyCandidateGetsTheSameOffsetForAnyKeys() throws Exception {
        ReportedKeyInputComparison comparison = new ReportedKeyInputComparison();
        List<String> mismatches = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(7);
        Field xRot = SimulationContext.class.getDeclaredField("xRot");
        xRot.setAccessible(true);
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            try (var f = new JavaTickFixture()) {
                PredictionResult[] last = {null};
                PredictionScenarios.run(f, seed, result -> {
                    if (result == null) return;
                    KnownInput held = f.player.packetStateData.knownInput;
                    try {
                        for (PredictionResult candidate : result.getAnyReality()) {
                            if (candidate.getValidMovements() == null) continue;
                            SimulationContext context = candidate.getSimulationContext();
                            float yaw = xRot.getFloat(context);
                            try {
                                for (int k = 0; k <= KEY_SETS; k++) {
                                    f.player.packetStateData.knownInput = k == 0 ? held : keys(random);
                                    xRot.setFloat(context, k == 0 ? yaw : yaw(random));
                                    String mismatch = comparison.compare(f.player, candidate, last[0]);
                                    if (mismatch != null) mismatches.add(scenario + ": " + mismatch);
                                }
                            } finally {
                                xRot.setFloat(context, yaw);
                            }
                        }
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    } finally {
                        f.player.packetStateData.knownInput = held;
                    }
                    last[0] = result;
                });
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(
                comparison.decided > SCENARIOS && comparison.nonZero > SCENARIOS,
                comparison.compared + " compared, " + comparison.decided + " decided, " + comparison.nonZero
                        + " non-zero");
        System.out.println(comparison.compared + " compared, " + comparison.decided + " decided, " + comparison.nonZero
                + " non-zero");
    }

    private static float yaw(SplittableRandom random) {
        return switch (random.nextInt(4)) {
            case 0 -> 45.0F * random.nextInt(-8, 9);
            case 1 -> (float) random.nextDouble(-1.0E7, 1.0E7);
            default -> (float) random.nextDouble(-360, 360);
        };
    }

    private static KnownInput keys(SplittableRandom random) {
        return new KnownInput(
                random.nextBoolean(),
                random.nextBoolean(),
                random.nextBoolean(),
                random.nextBoolean(),
                random.nextBoolean(),
                random.nextBoolean(),
                random.nextBoolean());
    }
}
