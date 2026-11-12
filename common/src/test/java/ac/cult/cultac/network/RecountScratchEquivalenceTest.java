package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.PredictionScratch;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The recount writes each scratch box before reading it, so whatever a box holds beforehand leaves its count. */
class RecountScratchEquivalenceTest {
    @Test
    void poisonedScratchRecountsTheSame() throws Exception {
        int nonZero = 0;
        int recounts = 0;
        for (long seed = 0; seed < 66; seed++) {
            if (PredictionScenarios.terrain(seed) != PredictionScenarios.Terrain.ENTITIES) continue;
            List<PredictionResult> predictions = new ArrayList<>();
            try (var f = new JavaTickFixture()) {
                PredictionScenarios.run(f, seed, result -> {
                    if (result != null) predictions.add(result);
                });
                PredictionScratch scratch = PredictionScratch.of(f.player);
                for (PredictionResult prediction : predictions) {
                    if (prediction.getSimulationContext() == null) continue;
                    WorldData world = prediction.getSimulationContext().getWorldData();
                    WorldStageBuilder.recountCollidingTickingAfterPlayer(f.player, prediction);
                    int clean = world.getNumCollidingTickingAfterPlayer();
                    for (int poison = 0; poison < 3; poison++) {
                        poison(scratch, poison);
                        WorldStageBuilder.recountCollidingTickingAfterPlayer(f.player, prediction);
                        assertEquals(clean, world.getNumCollidingTickingAfterPlayer(), "seed " + seed);
                    }
                    recounts++;
                    if (clean != 0) nonZero++;
                }
            }
        }
        assertTrue(recounts > 0 && nonZero > 0, recounts + " recounts, " + nonZero + " non-zero");
    }

    private static void poison(PredictionScratch scratch, int kind) {
        for (SimpleCollisionBox box : List.of(
                scratch.recountSweep,
                scratch.recountSweepScratch,
                scratch.recountContact,
                scratch.recountEntityBounds,
                scratch.recountEntityScratch)) {
            switch (kind) {
                case 0 -> box.set(-1.0E9, -1.0E9, -1.0E9, 1.0E9, 1.0E9, 1.0E9, true);
                case 1 -> box.set(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false);
                default -> box.set(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, true);
            }
        }
    }
}
