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
import java.util.Map;
import java.util.Set;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockGlideCancellationCaptureTest {
    private static final float[][] CAPTURE = {
        {2694.9705F, 67.97101F, -2962.4119F, .14694673F, -.37031642F, -.2943507F},
        {2695.1243F, 67.626724F, -2962.7375F, .15385333F, -.3442816F, -.32558468F},
        {2695.2844F, 67.305405F, -2963.0918F, .16026622F, -.32131827F, -.35412982F},
        {2695.4514F, 66.984085F, -2963.4644F, .15191446F, -.3932919F, -.33902872F},
        {2695.6099F, 66.59079F, -2963.8218F, .14423823F, -.46382606F, -.32531407F}
    };

    @Test public void sameStampConfirmationThenCancellationMatchesCapturedFlight() {
        var history = new BedrockActorHistory();
        var rewind = new BedrockMovementRewind(history);
        var state = seed();
        for (int i = 1; i < CAPTURE.length; i++) {
            long tick = 4910 + i;
            if (tick == 4912) {
                assertNull(history.metadata(4911, metadata(true)).gliding());
            }
            if (tick == 4913) {
                var before = history.current().getFirst().state();
                assertEquals(Boolean.FALSE, history.metadata(4911, metadata(false)).gliding());
                state = history.current().getFirst().state();
                assertEquals(before.velocity(), state.velocity());
                assertEquals(before.physicalFeetPosition(), state.physicalFeetPosition());
                rewind.queue(0, false, new BedrockReplayContextEvent(Map.of(), null, null, -1, null, false));
                var commit = new PredictionCommit(new BedrockNextTickStates(history.current()), Set.of());
                var delivered = rewind.apply(null, commit, (previous, world) -> world);
                state = BedrockProfileState.previousState(delivered.carry());
                assertFalse("ordinary item-use delivery must preserve the glide cancellation", state.gliding());
            }
            var frame = advance(state, tick, tick == 4911 ? Set.of("START_GLIDING") : Set.of());
            history.record(frame);
            state = frame.end().getFirst().state();
            assertEquals(tick < 4913, state.gliding());
            assertTrue("position at " + tick, state.physicalFeetPosition().subtract(position(CAPTURE[i])).length() <= .001);
            assertTrue("velocity at " + tick, state.velocity().subtract(velocity(CAPTURE[i])).length() <= .001);
        }
    }

    @Test public void laterExplicitStartAndStopOverrideHistoricalMetadataDuringReplay() {
        for (boolean start : new boolean[] {false, true}) {
            var history = new BedrockActorHistory();
            var first = advance(seed(), 4911, Set.of("START_GLIDING"));
            history.record(first);
            history.metadata(4911, metadata(!start));
            var second = advance(history.current().getFirst().state(), 4912,
                    Set.of(start ? "START_GLIDING" : "STOP_GLIDING"));
            history.record(second);
            assertEquals(start, history.current().getFirst().state().gliding());
            var replayed = history.apply(4911, new BedrockReplayEvent.Boost(0), (state, world) -> world);
            assertEquals(start, replayed.getFirst().state().gliding());
        }
    }

    private static BedrockReplayEvent.Metadata metadata(boolean gliding) {
        return new BedrockReplayEvent.Metadata(null, null, gliding, null, null, null, null);
    }

    private static BedrockMovementState seed() {
        return BedrockMovementState.fromPhysicalFeet(position(CAPTURE[0]), velocity(CAPTURE[0]),
                BedrockInputFrame.idle(4910), BedrockCollisionFlags.AIR)
                .withPlayerDimensions(new PlayerDimensionsState(.6F, .6F), true)
                .withAcknowledgedPose(null, null, true);
    }

    private static Vec3d position(float[] row) {
        return new Vec3d(row[0], (float) (row[1] - 1.62001F), row[2]);
    }

    private static Vec3d velocity(float[] row) { return new Vec3d(row[3], row[4], row[5]); }

    private static BedrockActorHistory.Frame advance(BedrockMovementState state, long tick, Set<String> actions) {
        var frame = new BedrockInputFrame(tick, tick == 4914 ? -160.3558F : -160.09595F,
                .90441895F, tick < 4913, false, false, actions);
        var world = BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE, AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of())),
                new EquipmentState(0, 0, 0, false, true), EntityContactState.NONE,
                new MovementModifierState(true, false, false, false, .05, false, false,
                        false, false, .35, 0), new PlayerDimensionsState(.6F, .6F)));
        var input = new BedrockSimulation.Input(state, frame, frame.intent(), world, false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockMobJumpComponentState.DEFAULT,
                true, false, new Vec3d(0, 0, 1));
        var candidate = BedrockForwardTick.simulate(input).getFirst();
        var result = candidate.movementResult();
        var entries = BedrockForwardTick.finish(result, result.predictedState()).stream()
                .map(next -> new BedrockProfileState.Entry(next, candidate.mobJumpComponent())).toList();
        return new BedrockActorHistory.Frame(tick, input, entries, entries, null, Vec3d.ZERO, Vec3d.ZERO,
                result.predictedPosition(), result.predictedVelocity(), List.of());
    }
}
