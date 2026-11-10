package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class BedrockSprintRewindTest {
    @Test
    public void stampedAttributeAppliesToTheNextFrameWithoutWaitingOrReplayingItsStampFrame() {
        var history = BedrockActorHistoryTest.history(1, 1);
        var rewind = new BedrockMovementRewind(history);
        var anchor = history.current().getFirst().state();
        // Stamped with the newest frame: the client has it before its next tick and does not re-run this one.
        rewind.queue(1, true, attribute(BedrockMovementAttributeState.serverValue(0.2F, false), true));
        var published = rewind.apply(null, commit(history.current()));
        var applied = BedrockProfileState.previousState(published.carry());
        assertEquals(1, applied.clientTick());
        assertEquals(anchor.physicalFeetPosition(), applied.physicalFeetPosition());
        assertEquals(anchor.velocity(), applied.velocity());
        assertEquals(0.2F, applied.movementAttribute().current(), 0);
        assertSame("Nothing is left to apply a frame late", published, rewind.apply(null, published));
        var start = forward(
                new BedrockProfileState.Entry(
                        applied, history.current().getFirst().mobJumpComponent()),
                2,
                Set.of("START_SPRINTING"));
        var state = start.end().getFirst().state();
        assertTrue(state.movementAttribute().hasSprintModifier());
        assertEquals(0.26F, state.movementAttribute().current(), 1e-7F);
        var stop = forward(
                new BedrockProfileState.Entry(state, start.end().getFirst().mobJumpComponent()),
                3,
                Set.of("SPRINTING", "STOP_SPRINTING"));
        assertEquals(0.2F, stop.end().getFirst().state().movementAttribute().current(), 0);
    }

    @Test
    public void stampedSprintConfirmationMatchingItsFrameIsNotAppliedToALaterState() {
        var history = BedrockActorHistoryTest.history(1, 1);
        var start = forward(history.current().getFirst(), 2, Set.of("START_SPRINTING"));
        history.record(start);
        var stop = forward(start.end().getFirst(), 3, Set.of("SPRINTING", "STOP_SPRINTING"));
        history.record(stop);
        var rewind = new BedrockMovementRewind(history);
        var current = commit(history.current());
        // The confirmation of the sprint that began on tick 2 arrives after the client stopped on tick 3.
        rewind.queue(2, true, attribute(BedrockMovementAttributeState.serverValue(0.1F, true), true));
        assertSame(current, rewind.apply(null, current));
        assertEquals(
                0.1F,
                BedrockProfileState.previousState(current.carry())
                        .movementAttribute()
                        .current(),
                0);
    }

    @Test
    public void anOrdinaryReplacementAcknowledgedWithAReplayIsKeptInEitherOrder() {
        var replacement =
                attribute(new BedrockMovementAttributeState(0.13000001F, 0, 1024, 0, 1024, 0.1F, List.of()), false);
        for (boolean replayFirst : List.of(true, false)) {
            var history = BedrockActorHistoryTest.history(1, 1);
            var anchor = history.current().getFirst().state();
            history.record(forward(history.current().getFirst(), 2, Set.of("START_SPRINTING")));
            var rewind = new BedrockMovementRewind(history);
            var motion = new BedrockReplayEvent.Motion(anchor.velocity());
            if (replayFirst) rewind.queue(1, true, motion);
            rewind.queue(0, false, replacement);
            if (!replayFirst) rewind.queue(1, true, motion);
            var published = rewind.apply(null, commit(history.current()));
            var state = BedrockProfileState.previousState(published.carry());
            // The replay re-runs the sprint start; the replacement the client also received still stands after it.
            assertTrue(state.sprinting());
            assertFalse(state.movementAttribute().hasSprintModifier());
            assertEquals(0.13000001F, state.movementAttribute().current(), 0);
        }
    }

    @Test
    public void groundUsesCurrentOnceEvenWhenActorFlagAndModifierDiffer() {
        var base = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(289, 82, -86), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND);
        for (boolean sprintFlag : List.of(false, true)) {
            var state = base.withSprinting(sprintFlag)
                    .withMovementAttribute(new BedrockMovementAttributeState(0.13F, 0, 1024, 0, 1024, 0.1F, List.of()));
            var result =
                    forward(new BedrockProfileState.Entry(state, BedrockMobJumpComponentState.DEFAULT), 1, Set.of());
            var end = result.end().getFirst().state();
            assertEquals(
                    0.1274,
                    end.physicalFeetPosition().z()
                            - state.physicalFeetPosition().z(),
                    1e-5);
            assertFalse(end.movementAttribute().hasSprintModifier());
        }
    }

    private static BedrockReplayContextEvent attribute(BedrockMovementAttributeState value, boolean historical) {
        return new BedrockReplayContextEvent(Map.of(), null, null, -1, null, null, value, historical);
    }

    private static PredictionCommit commit(List<BedrockProfileState.Entry> entries) {
        return new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }

    private static BedrockActorHistory.Frame forward(
            BedrockProfileState.Entry previous, long tick, Set<String> actions) {
        var frame =
                new BedrockInputFrame(tick, 0, 0, false, false, previous.state().sprinting(), actions);
        var input = new BedrockSimulation.Input(
                previous.state(),
                frame,
                frame.intent(),
                BedrockActorHistoryTest.ground(),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                previous.mobJumpComponent(),
                true,
                false,
                new Vec3d(0, 0, 1));
        var candidate = BedrockForwardTick.simulate(input).getFirst();
        var result = candidate.movementResult();
        var entries = BedrockForwardTick.finish(result, result.predictedState()).stream()
                .map(state -> new BedrockProfileState.Entry(state, candidate.mobJumpComponent()))
                .toList();
        return new BedrockActorHistory.Frame(
                tick,
                input,
                entries,
                entries,
                null,
                Vec3d.ZERO,
                Vec3d.ZERO,
                result.predictedState().physicalFeetPosition(),
                result.predictedState().velocity(),
                List.of());
    }
}
