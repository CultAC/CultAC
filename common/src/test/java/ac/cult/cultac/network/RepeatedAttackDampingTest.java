package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.SprintingAttacker.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * With a positive attack_knockback every hit on a player reaches Player#causeExtraKnockback, and the client's
 * consumeClick loop can attack several times in one tick: each hit multiplies X/Z by 0.6 in turn.
 */
class RepeatedAttackDampingTest {

    private static PredictionResult afterHits(SprintingAttacker s, int hits, double damping) {
        for (int i = 0; i < hits; i++) s.attack(TARGET);
        return s.move(damping);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6})
    void hitsWithoutTheirDampingFlag(int hits) throws Exception {
        try (var s = new SprintingAttacker(true)) {
            PredictionResult r = afterHits(s, hits, 1.0D);
            assertEquals(hits, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertTrue(s.keepSprint() > 0, describe(r));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 20})
    void hitsWithTheirDampingAreExact(int hits) throws Exception {
        try (var s = new SprintingAttacker(true)) {
            PredictionResult r = afterHits(s, hits, Math.pow(0.6D, hits));
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }

    @Test
    void sixCertainHitsAndADampingPossibleHitAreExact() throws Exception {
        try (var s = new SprintingAttacker(true)) {
            s.spawn(300, "minecraft:player");
            s.attack(300);
            PredictionResult r = afterHits(s, 6, Math.pow(0.6D, 7));
            assertEquals(6, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertEquals(7, r.getSimulationContext().getMaxAttackSlow(), describe(r));
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }
}
