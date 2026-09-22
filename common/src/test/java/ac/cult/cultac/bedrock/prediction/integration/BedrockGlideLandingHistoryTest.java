package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockGlideLandingHistoryTest {
    @Test public void cancelledGlideMatchesFlag93And111MovementWithinOneMillimetre() {
        assertCancelledTick(23380, 144.92249F, 14.281937F, false,
                new Vec3d(2887.422607421875, 65.6666030883789, -121.23355865478516),
                new Vec3d(-0.20048879086971283, 0.0868678167462349, -0.28317922353744507),
                new Vec3d(2887.20751953125, 65.75347137451172, -121.5376205444336),
                new Vec3d(-0.19576994F, 0.0067304624F, -0.2766687F));
        assertCancelledTick(23457, 145.702F, 18.699356F, true,
                new Vec3d(2872.152099609375, 64.40251922607422, -143.3949432373047),
                new Vec3d(-0.18572531640529633, 0.025752030313014984, -0.2722720503807068),
                new Vec3d(2871.951904296875, 64.42826843261719, -143.68826293945312),
                new Vec3d(-0.18207577F, -0.05316301F, -0.26692265F));
    }

    private static void assertCancelledTick(long tick, float yaw, float pitch, boolean jumping,
            Vec3d position, Vec3d velocity, Vec3d expectedPosition, Vec3d expectedVelocity) {
        var state = BedrockMovementState.fromPhysicalFeet(position, velocity,
                BedrockInputFrame.idle(tick - 1), BedrockCollisionFlags.AIR)
                .applySprintAction(true).withGliding(true);
        state = glide(false).state(state);
        var frame = new BedrockInputFrame(tick, yaw, pitch, jumping, false, true,
                Set.of("UP", "SPRINTING", "SPRINT_DOWN"));
        var input = new BedrockSimulation.Input(state, frame, frame.intent(), BedrockActorHistoryTest.ground(),
                false, BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockMobJumpComponentState.DEFAULT,
                true, false, new Vec3d(0, 0, 1));
        var result = BedrockForwardTick.simulate(input).getFirst().movementResult();
        assertFalse(result.selectedGlidingTravel());
        assertTrue("position " + tick + ": " + result.predictedPosition(),
                result.predictedPosition().subtract(expectedPosition).length() <= 0.001);
        assertTrue("velocity " + tick + ": " + result.predictedVelocity(),
                result.predictedVelocity().subtract(expectedVelocity).length() <= 0.001);
    }

    @Test public void landingRetainsFlagUntilNextGroundedActionPhase() {
        var landed = landing().end().getFirst().state();
        assertTrue(landed.collisionFlags().onGround());
        assertEquals(82.0, landed.physicalFeetPosition().y(), 0.001);
        assertTrue(landed.gliding());
        assertTrue(landed.glidingRequest());
        var stopped = advance(landed, 2).end().getFirst().state();
        assertFalse(stopped.gliding());
        assertFalse(stopped.glidingRequest());
    }

    @Test public void delayedLandingCancellationStopsNewerGlideWithoutMovingHistory() {
        var history = new BedrockActorHistory();
        var landing = landing();
        history.record(landing);
        var stopped = advance(landing.end().getFirst().state(), 2);
        history.record(stopped);
        var airborne = stopped.end().getFirst().state()
                .withPhysicalFeetPosition(new Vec3d(289, 84, -86), 0)
                .withVelocityAndCollisionFlags(new Vec3d(0.2, -0.1, 0), BedrockCollisionFlags.AIR)
                .withGliding(true);
        history.record(advance(airborne, 3));
        var before = history.current().getFirst().state();
        assertTrue(before.gliding());
        assertEquals(Boolean.FALSE, history.metadata(1, glide(false)).gliding());
        var after = history.current().getFirst().state();
        assertFalse(after.gliding());
        assertTrue(after.glidingRequest());
        assertEquals(before.fallFlyTicks(), after.fallFlyTicks());
        assertEquals(before.velocity(), after.velocity());
        assertEquals(before.physicalFeetPosition(), after.physicalFeetPosition());
        assertEquals(before.simulationTick(), after.simulationTick());
        assertEquals(landing.end(), history.frames().getFirst().end());
        assertNull(history.metadata(2, glide(false)).gliding());
        var next = advance(after, 4).end().getFirst().state();
        assertFalse(next.gliding());
        assertTrue(next.glidingRequest());
    }

    @Test public void serverFlagDoesNotInventLocalGlideRequest() {
        var base = landing().input().previousState().withGliding(false);
        var serverGlide = glide(true).state(base);
        assertTrue(serverGlide.gliding());
        assertFalse(serverGlide.glidingRequest());
        var next = advance(serverGlide, 1).end().getFirst().state();
        assertTrue(next.gliding());
        assertFalse(next.glidingRequest());
    }

    private static BedrockReplayEvent.Metadata glide(boolean value) {
        return new BedrockReplayEvent.Metadata(null, null, value, null, null, null, null);
    }

    private static BedrockActorHistory.Frame landing() {
        return advance(BedrockMovementState.fromPhysicalFeet(new Vec3d(289, 82.02, -86),
                new Vec3d(0.2, -0.23, 0), BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR)
                .withGliding(true), 1);
    }

    private static BedrockActorHistory.Frame advance(BedrockMovementState previous, long tick) {
        var frame = BedrockInputFrame.idle(tick);
        var input = new BedrockSimulation.Input(previous, frame, frame.intent(), BedrockActorHistoryTest.ground(),
                false, BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockMobJumpComponentState.DEFAULT,
                true, false, Vec3d.ZERO);
        var result = BedrockForwardTick.simulate(input).getFirst();
        var state = result.movementResult().predictedState();
        var entries = BedrockForwardTick.finish(result.movementResult(), state).stream()
                .map(next -> new BedrockProfileState.Entry(next, result.mobJumpComponent())).toList();
        return new BedrockActorHistory.Frame(tick, input, entries, entries, null, Vec3d.ZERO, Vec3d.ZERO,
                state.physicalFeetPosition(), state.velocity(), List.of());
    }
}
