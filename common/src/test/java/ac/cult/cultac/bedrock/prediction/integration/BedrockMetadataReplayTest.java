package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockMetadataReplayTest {
    private static final BedrockReplayEvent.Metadata CANCEL =
            new BedrockReplayEvent.Metadata(null, null, false, null, null, null, null);

    @Test public void boostReplayAppliesSavedCancellationBeforeLaterStartInsteadOfAtReceipt() {
        var history = history();
        var before = history.current().getFirst().state();
        history.metadata(2, CANCEL);
        assertFalse(history.current().getFirst().state().gliding());
        assertEquals(before.physicalFeetPosition(), history.current().getFirst().state().physicalFeetPosition());
        assertEquals(before.velocity(), history.current().getFirst().state().velocity());

        var expected = history.frames().getFirst().end().getFirst().state();
        for (long tick = 2; tick <= 5; tick++) {
            expected = forward(expected, tick, true).end().getFirst().state();
            if (tick == 2) expected = CANCEL.state(expected);
        }
        var actual = history.apply(1, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state();
        assertTrue(actual.gliding());
        assertEquals(expected.physicalFeetPosition(), actual.physicalFeetPosition());
        assertEquals(expected.velocity(), actual.velocity());
        assertEquals(expected.glidingRequest(), actual.glidingRequest());
        assertTrue(actual.physicalFeetPosition().subtract(before.physicalFeetPosition()).length() > 0.001);
    }

    @Test public void replayAtMetadataAnchorIncludesTheSavedMask() {
        var history = history();
        history.metadata(2, CANCEL);
        var expected = CANCEL.state(history.frames().get(1).beforeEvents().getFirst().state());
        for (long tick = 3; tick <= 5; tick++) expected = forward(expected, tick, true).end().getFirst().state();
        var actual = history.apply(2, new BedrockReplayEvent.Boost(100), (state, world) -> world)
                .getFirst().state();
        assertEquals(expected.physicalFeetPosition(), actual.physicalFeetPosition());
        assertEquals(expected.velocity(), actual.velocity());
        assertTrue(actual.gliding());
    }

    @Test public void dimensionDeliveryIsSeparateFromHistoricalFlagPlacement() {
        var history = history();
        history.metadata(2, new BedrockReplayEvent.Metadata(null, 1.8F, false, null, null, null, null));
        var historical = (BedrockReplayEvent.Metadata) history.frames().get(1).events().getFirst().value();
        assertEquals(CANCEL, historical);
        var ordinary = (BedrockReplayEvent.Metadata) history.frames().getLast().events().getFirst().value();
        assertNull(ordinary.gliding());
        assertEquals(Float.valueOf(1.8F), ordinary.height());
        assertEquals(1.8F, history.current().getFirst().state().playerDimensions().height(), 0);
    }

    @Test public void confirmationDoesNotCreateAnEventAndCopiedHistoryRetainsMasks() {
        var history = history();
        history.metadata(2, new BedrockReplayEvent.Metadata(null, null, true, null, null, null, null));
        assertTrue(history.frames().stream().allMatch(frame -> frame.events().isEmpty()));
        history.metadata(2, CANCEL);
        var copy = history.copy();
        copy.apply(1, new BedrockReplayEvent.Boost(100), (state, world) -> world);
        assertTrue(copy.current().getFirst().state().gliding());
        assertFalse(history.current().getFirst().state().gliding());
    }

    private static BedrockActorHistory history() {
        var history = new BedrockActorHistory();
        var state = BedrockMovementState.fromPhysicalFeet(new Vec3d(0, 80, 0), new Vec3d(0.1, -0.1, 0),
                BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
        for (long tick = 1; tick <= 5; tick++) {
            var frame = forward(state, tick, false);
            history.record(frame);
            state = frame.end().getFirst().state();
        }
        return history;
    }

    private static BedrockActorHistory.Frame forward(BedrockMovementState state, long tick, boolean boost) {
        var frame = new BedrockInputFrame(tick, -75, -4, false, false, false,
                tick == 2 || tick == 4 ? Set.of("START_GLIDING") : Set.of());
        var world = BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE, AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of())),
                new EquipmentState(0, 0, 0, false, true), EntityContactState.NONE,
                new MovementModifierState(true, false, false, false, 0.05, false, false,
                        false, false, 0.35, 0), PlayerDimensionsState.DEFAULT));
        var input = new BedrockSimulation.Input(state, frame, frame.intent(), world, false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockMobJumpComponentState.DEFAULT,
                true, false, Vec3d.ZERO, boost);
        var candidate = BedrockForwardTick.simulate(input).getFirst();
        var result = candidate.movementResult();
        var entries = BedrockForwardTick.finish(result, result.predictedState()).stream()
                .map(next -> new BedrockProfileState.Entry(next, candidate.mobJumpComponent())).toList();
        return new BedrockActorHistory.Frame(tick, input, entries, entries, null, Vec3d.ZERO, Vec3d.ZERO,
                result.predictedPosition(), result.predictedVelocity(), List.of());
    }
}
