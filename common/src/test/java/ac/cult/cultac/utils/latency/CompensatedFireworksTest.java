package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.PacketStateData;
import org.junit.jupiter.api.Test;

/** One FireworkRocketEntity boosts its attached glider once per tick, however often its metadata is re-sent. */
class CompensatedFireworksTest {
    @Test
    void reSentAttachedMetadataCountsTheRocketOnce() {
        CompensatedFireworks fireworks = new CompensatedFireworks(mock(CultPlayer.class));
        fireworks.addNewFirework(7);
        fireworks.addNewFirework(7);
        fireworks.addNewFirework(8);

        assertEquals(2, fireworks.activeFireworks.size());

        fireworks.removeFirework(7);
        fireworks.onPredictionComplete(new PredictionComplete(mock(PredictionResult.class)));

        assertEquals(1, fireworks.activeFireworks.size());
    }

    @Test
    void destroyedEntitiesWithoutAPredictionQueueOnlyTrackedRockets() {
        CompensatedFireworks fireworks = new CompensatedFireworks(mock(CultPlayer.class));
        fireworks.addNewFirework(7);
        for (int entityId = 100; entityId < 100_000; entityId++) fireworks.removeFirework(entityId);
        fireworks.removeFirework(7);
        fireworks.removeFirework(7);

        assertEquals(1, fireworks.fireworksToRemoveNextTick.size());
        assertEquals(1, fireworks.activeFireworks.size());
    }

    @Test
    void rocketDestroyedDuringALongPassengerRideIsRemovedAtTheNextPrediction() {
        CompensatedFireworks fireworks = new CompensatedFireworks(mock(CultPlayer.class));
        fireworks.addNewFirework(7);
        for (int entityId = 100; entityId < 100_000; entityId++) {
            fireworks.removeFirework(entityId);
            if (entityId == 50_000) fireworks.removeFirework(7);
        }
        fireworks.addNewFirework(8);

        fireworks.onPredictionComplete(new PredictionComplete(mock(PredictionResult.class)));

        assertEquals(1, fireworks.activeFireworks.size());
        assertTrue(fireworks.fireworksToRemoveNextTick.isEmpty());
        assertEquals(8, fireworks.activeFireworks.getInt(0));
    }

    /** ClientPacketListener#handleRespawn's new LocalPlayer is not the entity the old rockets are attached to. */
    @Test
    void aRespawnedPlayerHasNoRocketsAttached() {
        CompensatedFireworks fireworks = glidingWithRockets(7, 8);
        assertEquals(2, fireworks.getMaxFireworksAppliedPossible());

        fireworks.localPlayerReplaced(false);
        fireworks.onPredictionComplete(glidingTick());

        assertTrue(fireworks.activeFireworks.isEmpty());
        assertEquals(0, fireworks.getMaxFireworksAppliedPossible());
    }

    /** The new LocalPlayer copies the boosted delta movement only with KEEP_ENTITY_DATA. */
    @Test
    void aRespawnKeepingEntityDataKeepsTheLastBoost() {
        CompensatedFireworks kept = glidingWithRockets(7);
        kept.localPlayerReplaced(true);
        assertEquals(1, kept.getMaxFireworksAppliedPossible());

        CompensatedFireworks reset = glidingWithRockets(7);
        reset.localPlayerReplaced(false);
        assertEquals(0, reset.getMaxFireworksAppliedPossible());
    }

    /** TickRateManager#isEntityFrozen stops every rocket while the ticks are frozen, except on a tick step. */
    @Test
    void frozenTicksStopTheRocketsUnlessStepping() {
        CultPlayer player = player();
        player.packetStateData.serverTicksFrozen = true;
        CompensatedFireworks frozen = glidingWithRockets(player, 7, 8);
        assertEquals(0, frozen.getMaxFireworksAppliedPossible());

        player.packetStateData.serverFrozenTickStepsRemaining = 1;
        frozen.onPredictionComplete(glidingTick());
        assertEquals(2, frozen.getMaxFireworksAppliedPossible());
    }

    private static CompensatedFireworks glidingWithRockets(int... rockets) {
        return glidingWithRockets(player(), rockets);
    }

    private static CompensatedFireworks glidingWithRockets(CultPlayer player, int... rockets) {
        CompensatedFireworks fireworks = new CompensatedFireworks(player);
        for (int rocket : rockets) fireworks.addNewFirework(rocket);
        fireworks.onPredictionComplete(glidingTick());
        return fireworks;
    }

    private static CultPlayer player() {
        CultPlayer player = mock(CultPlayer.class);
        player.packetStateData = new PacketStateData();
        return player;
    }

    private static PredictionComplete glidingTick() {
        SimulationContext gliding = mock(SimulationContext.class);
        when(gliding.isGliding()).thenReturn(true);
        PredictionResult tick = mock(PredictionResult.class);
        when(tick.getSimulationContext()).thenReturn(gliding);
        return new PredictionComplete(tick);
    }
}
