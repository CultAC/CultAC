package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import org.junit.jupiter.api.Test;

/** One FireworkRocketEntity boosts its attached glider once per tick, however often its metadata is re-sent. */
class CompensatedFireworksTest {
    private static CultPlayer bedrockPlayer() {
        CultPlayer player = mock(CultPlayer.class);
        when(player.isBedrockMovement()).thenReturn(true);
        return player;
    }

    @Test
    void aJavaRemovalTakesEffectAtItsPacketProof() {
        CompensatedFireworks fireworks = new CompensatedFireworks(mock(CultPlayer.class));
        fireworks.addNewFirework(7);
        fireworks.removeFirework(7);
        assertEquals(0, fireworks.getMaxFireworksAppliedPossible());
        assertTrue(fireworks.fireworksToRemoveNextTick.isEmpty());
    }

    @Test
    void reSentAttachedMetadataCountsTheRocketOnce() {
        CompensatedFireworks fireworks = new CompensatedFireworks(bedrockPlayer());
        fireworks.addNewFirework(7);
        fireworks.addNewFirework(7);
        fireworks.addNewFirework(8);

        assertEquals(2, fireworks.getMaxFireworksAppliedPossible());

        fireworks.removeFirework(7);
        fireworks.onPredictionComplete(mock(PredictionComplete.class));

        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
    }

    @Test
    void destroyedEntitiesWithoutAPredictionQueueOnlyTrackedRockets() {
        CompensatedFireworks fireworks = new CompensatedFireworks(bedrockPlayer());
        fireworks.addNewFirework(7);
        for (int entityId = 100; entityId < 100_000; entityId++) fireworks.removeFirework(entityId);
        fireworks.removeFirework(7);
        fireworks.removeFirework(7);

        assertEquals(1, fireworks.fireworksToRemoveNextTick.size());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
    }

    @Test
    void rocketDestroyedDuringALongPassengerRideIsRemovedAtTheNextPrediction() {
        CompensatedFireworks fireworks = new CompensatedFireworks(bedrockPlayer());
        fireworks.addNewFirework(7);
        for (int entityId = 100; entityId < 100_000; entityId++) {
            fireworks.removeFirework(entityId);
            if (entityId == 50_000) fireworks.removeFirework(7);
        }
        fireworks.addNewFirework(8);

        fireworks.onPredictionComplete(mock(PredictionComplete.class));

        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertTrue(fireworks.fireworksToRemoveNextTick.isEmpty());
        assertEquals(8, fireworks.activeFireworks.getInt(0));
    }
}
