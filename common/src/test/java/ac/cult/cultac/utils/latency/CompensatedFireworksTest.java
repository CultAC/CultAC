package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.math.TrigHandler;
import java.lang.reflect.Field;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** One FireworkRocketEntity boosts its attached glider once per tick, however often its metadata is re-sent. */
class CompensatedFireworksTest {
    private static final int SELF = 1;
    private static final int OTHER = 2;
    private static final int ATTACHED_AT = 5;

    @Test
    void reSentAttachedMetadataCountsTheRocketOnce() throws Exception {
        CompensatedFireworks fireworks = fireworks(player(ATTACHED_AT));
        attach(fireworks, 7, 8, 7);
        fireworks.onPredictionComplete(glidingTick());

        assertEquals(2, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(2, fireworks.getCertainFireworksApplied());
    }

    @Test
    void destroyedEntitiesQueueOnlyTrackedRockets() throws Exception {
        CompensatedFireworks fireworks = fireworks(player(ATTACHED_AT));
        attach(fireworks, 7);
        for (int entityId = 100; entityId < 100_000; entityId++) fireworks.removalSent(List.of(entityId), 9);
        fireworks.removalSent(List.of(7), 9);
        fireworks.removalSent(List.of(7), 10);

        assertEquals(1, fireworks.rockets.size());
        assertEquals(9, fireworks.rockets.get(7).removedAt);
    }

    /** A rocket ticks from the first client tick after the client applies its attachment. */
    @Test
    void aRocketBoostsCertainlyOnceTheClientAnswersThePingAfterItsAttachment() throws Exception {
        CultPlayer player = player(ATTACHED_AT - 1);
        CompensatedFireworks fireworks = fireworks(player);
        attach(fireworks, 7);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(0, fireworks.getCertainFireworksApplied());

        player.lastTransactionReceived.set(ATTACHED_AT);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getCertainFireworksApplied());
    }

    /** ClientLevel#removeEntity stops a rocket before the first client tick after the client applies the removal. */
    @Test
    void aRemovedRocketMayBoostUntilTheClientAnswersTheRemovalsProof() throws Exception {
        CultPlayer player = player(ATTACHED_AT);
        CompensatedFireworks fireworks = fireworks(player);
        attach(fireworks, 7, 8);
        fireworks.removalSent(List.of(7), 9);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(2, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(1, fireworks.getCertainFireworksApplied());

        player.lastTransactionReceived.set(9);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(1, fireworks.getCertainFireworksApplied());
        assertEquals(1, fireworks.rockets.size());
    }

    /** FireworkRocketEntity#tick stops boosting once the client applies an empty target, and the rocket flies off. */
    @Test
    void aDetachedRocketStopsOnceTheClientAnswersTheDetachmentsProof() throws Exception {
        CultPlayer player = player(ATTACHED_AT);
        CompensatedFireworks fireworks = fireworks(player);
        attach(fireworks, 7);
        fireworks.onPredictionComplete(glidingTick());
        fireworks.attachmentChanged(7, OptionalInt.empty(), ATTACHED_AT + 1);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(0, fireworks.getCertainFireworksApplied());

        player.lastTransactionReceived.set(ATTACHED_AT + 1);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(0, fireworks.getMaxFireworksAppliedPossible());

        fireworks.attachmentChanged(7, OptionalInt.of(SELF), ATTACHED_AT + 2);
        player.lastTransactionReceived.set(ATTACHED_AT + 2);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(0, fireworks.getCertainFireworksApplied());
    }

    /** FireworkRocketEntity#tick keeps the entity it resolved while any target is set. */
    @Test
    void aRocketHoldingThePlayerKeepsBoostingItWhenRetargeted() throws Exception {
        CultPlayer player = player(ATTACHED_AT);
        CompensatedFireworks fireworks = fireworks(player);
        attach(fireworks, 7);
        fireworks.onPredictionComplete(glidingTick());
        fireworks.attachmentChanged(7, OptionalInt.of(OTHER), ATTACHED_AT + 1);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getCertainFireworksApplied());

        player.lastTransactionReceived.set(ATTACHED_AT + 1);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(1, fireworks.getCertainFireworksApplied());
    }

    /** A rocket that may have resolved another entity first keeps that entity. */
    @Test
    void aRocketThatMayHoldAnotherEntityIsNeverCertain() throws Exception {
        CultPlayer player = player(ATTACHED_AT);
        CompensatedFireworks retargeted = fireworks(player);
        attach(retargeted, 7);
        retargeted.attachmentChanged(7, OptionalInt.of(OTHER), ATTACHED_AT + 1);
        player.lastTransactionReceived.set(ATTACHED_AT + 10);
        retargeted.onPredictionComplete(glidingTick());
        assertEquals(1, retargeted.getMaxFireworksAppliedPossible());
        assertEquals(0, retargeted.getCertainFireworksApplied());

        CultPlayer firstElsewhere = player(ATTACHED_AT);
        CompensatedFireworks elsewhere = fireworks(firstElsewhere);
        elsewhere.attachmentChanged(7, OptionalInt.of(OTHER), ATTACHED_AT);
        elsewhere.onPredictionComplete(glidingTick());
        assertEquals(0, elsewhere.getMaxFireworksAppliedPossible());

        elsewhere.attachmentChanged(7, OptionalInt.of(SELF), ATTACHED_AT + 1);
        firstElsewhere.lastTransactionReceived.set(ATTACHED_AT + 1);
        elsewhere.onPredictionComplete(glidingTick());
        assertEquals(1, elsewhere.getMaxFireworksAppliedPossible());
        assertEquals(0, elsewhere.getCertainFireworksApplied());
    }

    /** ClientLevel#tickEntities ticks only the rockets in chunks the client has loaded. */
    @Test
    void aRocketOutsideTheProvenChunksMayHaveStopped() throws Exception {
        CultPlayer player = player(ATTACHED_AT);
        when(player.compensatedWorld.isColumnProven(anyInt(), anyInt())).thenReturn(false);
        CompensatedFireworks fireworks = fireworks(player);
        attach(fireworks, 7);
        fireworks.onPredictionComplete(glidingTick());

        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(0, fireworks.getCertainFireworksApplied());
    }

    /** A rocket that missed a tick stays where it stopped until the client applies a position update for it. */
    @Test
    void aRocketThatMayHaveStoppedIsUncertainUntilItsPositionIsApplied() throws Exception {
        CultPlayer player = player(ATTACHED_AT);
        CompensatedFireworks fireworks = fireworks(player);
        attach(fireworks, 7);
        when(player.compensatedWorld.isColumnProven(anyInt(), anyInt())).thenReturn(false);
        fireworks.onPredictionComplete(glidingTick());
        when(player.compensatedWorld.isColumnProven(anyInt(), anyInt())).thenReturn(true);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(0, fireworks.getCertainFireworksApplied());

        fireworks.positionApplied(7, ATTACHED_AT);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(0, fireworks.getCertainFireworksApplied());

        fireworks.positionApplied(7, ATTACHED_AT + 1);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getCertainFireworksApplied());
    }

    /** A rocket the server adds again under a removed rocket's id boosts certainly once its attachment is proven. */
    @Test
    void aRocketAddedAgainUnderItsIdBoostsOnceProven() throws Exception {
        CultPlayer player = player(ATTACHED_AT);
        CompensatedFireworks fireworks = fireworks(player);
        attach(fireworks, 7);
        fireworks.onPredictionComplete(glidingTick());
        fireworks.removalSent(List.of(7), ATTACHED_AT + 1);
        fireworks.attachmentChanged(7, OptionalInt.of(SELF), ATTACHED_AT + 2);
        player.lastTransactionReceived.set(ATTACHED_AT + 1);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(0, fireworks.getCertainFireworksApplied());

        player.lastTransactionReceived.set(ATTACHED_AT + 2);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(1, fireworks.getCertainFireworksApplied());
    }

    /** Entity#checkBelowWorld discards a rocket that starts its tick below the level, after which it boosts once. */
    @Test
    void aRocketBelowTheWorldBoostsOnceMore() throws Exception {
        CultPlayer player = player(ATTACHED_AT);
        player.y = -65;
        CompensatedFireworks fireworks = fireworks(player);
        attach(fireworks, 7);
        fireworks.onPredictionComplete(glidingTick());
        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(1, fireworks.getCertainFireworksApplied());

        fireworks.onPredictionComplete(glidingTick());
        assertEquals(0, fireworks.getMaxFireworksAppliedPossible());
        assertEquals(0, fireworks.rockets.size());
    }

    /** ClientPacketListener#handleRespawn's new LocalPlayer is not the entity the old rockets are attached to. */
    @Test
    void aRespawnedPlayerHasNoRocketsAttached() throws Exception {
        CompensatedFireworks fireworks = glidingWithRockets(player(ATTACHED_AT), 7, 8);
        assertEquals(2, fireworks.getMaxFireworksAppliedPossible());

        fireworks.localPlayerReplaced(false);
        fireworks.onPredictionComplete(glidingTick());

        assertEquals(0, fireworks.rockets.size());
        assertEquals(0, fireworks.getMaxFireworksAppliedPossible());
    }

    /** TickRateManager#isEntityFrozen stops every rocket while the ticks are frozen, except on a tick step. */
    @Test
    void frozenTicksStopTheRocketsUnlessStepping() throws Exception {
        CultPlayer player = player(ATTACHED_AT);
        CompensatedFireworks frozen = fireworks(player);
        player.packetStateData.serverTicksFrozen = true;
        attach(frozen, 7, 8);
        frozen.onPredictionComplete(glidingTick());
        assertEquals(0, frozen.getMaxFireworksAppliedPossible());
        assertEquals(0, frozen.getCertainFireworksApplied());

        player.packetStateData.serverFrozenTickStepsRemaining = 1;
        frozen.onPredictionComplete(glidingTick());
        assertEquals(2, frozen.getMaxFireworksAppliedPossible());
        assertEquals(2, frozen.getCertainFireworksApplied());
    }

    private static void attach(CompensatedFireworks fireworks, int... rockets) {
        for (int rocket : rockets) fireworks.attachmentChanged(rocket, OptionalInt.of(SELF), ATTACHED_AT);
    }

    /** Rockets for {@code player}, after one tick that places them at it. */
    private static CompensatedFireworks fireworks(CultPlayer player) {
        CompensatedFireworks fireworks = new CompensatedFireworks(player);
        fireworks.onPredictionComplete(glidingTick());
        return fireworks;
    }

    private static CompensatedFireworks glidingWithRockets(CultPlayer player, int... rockets) {
        CompensatedFireworks fireworks = fireworks(player);
        attach(fireworks, rockets);
        fireworks.onPredictionComplete(glidingTick());
        return fireworks;
    }

    /** A player in loaded chunks whose client has answered every ping through {@code received}. */
    private static CultPlayer player(int received) throws Exception {
        CultPlayer player = mock(CultPlayer.class);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_26_2);
        player.packetStateData = new PacketStateData();
        player.entityID = SELF;
        player.trigHandler = new TrigHandler(player);
        player.compensatedEntities = mock(CompensatedEntities.class);
        player.compensatedWorld = mock(CompensatedWorld.class);
        when(player.compensatedWorld.isColumnProven(anyInt(), anyInt())).thenReturn(true);
        Field field = CultPlayer.class.getDeclaredField("lastTransactionReceived");
        field.setAccessible(true);
        field.set(player, new AtomicInteger(received));
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
