package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.SprintingAttacker.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import org.junit.jupiter.api.Test;

/**
 * With a positive attack_knockback every hit on a player reaches Player#causeExtraKnockback, and the client's
 * consumeClick loop can attack several times in one tick: each hit multiplies X/Z by 0.6 in turn.
 */
class RepeatedAttackDampingTest {

    private static PredictionResult afterHits(SprintingAttacker s, int hits, double damping) {
        for (int i = 0; i < hits; i++) s.attack(TARGET);
        return s.move(damping);
    }

    @Test
    void fiveHitsWithoutTheirDampingFlag() throws Exception {
        try (var s = new SprintingAttacker(true)) {
            PredictionResult r = afterHits(s, 5, 1.0D);
            assertEquals(5, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertTrue(s.keepSprint() > 0, describe(r));
        }
    }

    @Test
    void sixHitsWithoutTheirDampingFlag() throws Exception {
        try (var s = new SprintingAttacker(true)) {
            PredictionResult r = afterHits(s, 6, 1.0D);
            assertEquals(6, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertTrue(s.keepSprint() > 0, describe(r));
        }
    }

    @Test
    void sixHitsWithTheirDampingAreExact() throws Exception {
        try (var s = new SprintingAttacker(true)) {
            PredictionResult r = afterHits(s, 6, Math.pow(0.6D, 6));
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

    @Test
    void twentyHitsWithTheirDampingAreExact() throws Exception {
        try (var s = new SprintingAttacker(true)) {
            PredictionResult r = afterHits(s, 20, Math.pow(0.6D, 20));
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }
}
