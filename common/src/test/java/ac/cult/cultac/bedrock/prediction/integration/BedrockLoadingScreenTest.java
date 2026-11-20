package ac.cult.cultac.bedrock.prediction.integration;

import static org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.*;
import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.player.BedrockLoadingScreenState;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import org.junit.Test;

public final class BedrockLoadingScreenTest {
    @Test
    public void continuationPreservesActiveAndAnticipatedScreensWithoutReusingThem() {
        var previous = new BedrockLoadingScreenState();
        previous.startGame();
        previous.changeDimension(42);
        assertTrue(previous.accept(START_LOADING_SCREEN, null));
        var continued = new BedrockLoadingScreenState();
        continued.restore(previous.continuation());
        assertTrue(continued.active());
        assertFalse(continued.accept(END_LOADING_SCREEN, 42));
        assertTrue(continued.accept(END_LOADING_SCREEN, null));
        assertFalse(continued.accept(START_LOADING_SCREEN, null));
        assertTrue(continued.accept(START_LOADING_SCREEN, 42));
        assertTrue(continued.accept(END_LOADING_SCREEN, 42));
        assertFalse(continued.accept(START_LOADING_SCREEN, 42));
    }

    @Test
    public void loadingRetainsExactStateAndTeleportUntilTheEndBoundary() {
        var loading = new BedrockLoadingScreenState();
        loading.startGame();
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        var position = new Vec3d(289, 82, -86);
        var state = BedrockMovementState.fromPhysicalFeet(
                        position, Vec3d.ZERO, BedrockInputFrame.idle(1), BedrockCollisionFlags.AIR)
                .withTeleportPending();
        // Real 1.26.51 join: repeated stationary auth inputs with zero future velocity.
        for (int tick = 2; tick <= 7; tick++) {
            state = advance(state, tick, loading);
            assertEquals(position, state.physicalFeetPosition());
            assertEquals(Vec3d.ZERO, state.velocity());
        }
        assertTrue(loading.accept(END_LOADING_SCREEN, null));
        state = advance(state, 8, loading);
        assertEquals(position, state.physicalFeetPosition());
        // The first actor tick consumes the pending teleport; gravity starts on the next tick.
        // Live capture f4ea4fd1: end-loading precedes auth 323 (delta 0), then 324 (delta -0.0784).
        assertEquals(Vec3d.ZERO, state.velocity());
        state = advance(state, 9, loading);
        assertEquals(-0.0784F, state.velocity().y(), 1e-8);
    }

    @Test
    public void aLoadingScreenCannotBeInventedReusedOrClosedWithAnotherId() {
        var loading = new BedrockLoadingScreenState();
        assertFalse(loading.accept(START_LOADING_SCREEN, null));
        assertFalse(loading.active());
        loading.startGame();
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        assertFalse(loading.accept(END_LOADING_SCREEN, 42));
        assertTrue(loading.active());
        assertTrue(loading.accept(END_LOADING_SCREEN, null));
        assertFalse(loading.accept(START_LOADING_SCREEN, null));
        loading.changeDimension(42);
        assertFalse(loading.accept(START_LOADING_SCREEN, 43));
        assertTrue(loading.accept(START_LOADING_SCREEN, 42));
        assertTrue(loading.accept(END_LOADING_SCREEN, 42));
        assertFalse(loading.accept(START_LOADING_SCREEN, 42));
    }

    private static BedrockMovementState advance(
            BedrockMovementState previous, long tick, BedrockLoadingScreenState loading) {
        var frame = BedrockInputFrame.idle(tick);
        var input = new BedrockSimulation.Input(
                previous,
                frame,
                frame.intent(),
                BedrockActorHistoryTest.ground(),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                !loading.active(),
                false,
                Vec3d.ZERO);
        return BedrockSimulation.candidates(input).getFirst().movementResult().predictedState();
    }
}
