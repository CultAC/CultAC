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
import ac.cult.cultac.utils.math.Vec3;
import org.junit.Test;

public final class BedrockLoadingScreenTest {
    // Geyser's ChangeDimensionPacket position; the client reports it, plus its eye height, while the screen shows.
    private static final Vec3 DIMENSION_SPAWN = new Vec3(0, 32767, 0);

    @Test
    public void continuationPreservesActiveAndAnticipatedScreensWithoutReusingThem() {
        var previous = new BedrockLoadingScreenState();
        previous.startGame();
        previous.changeDimension(42, DIMENSION_SPAWN);
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
        loading.changeDimension(42, DIMENSION_SPAWN);
        assertFalse(loading.accept(START_LOADING_SCREEN, 43));
        assertTrue(loading.accept(START_LOADING_SCREEN, 42));
        assertTrue(loading.accept(END_LOADING_SCREEN, 42));
        assertFalse(loading.accept(START_LOADING_SCREEN, 42));
    }

    @Test
    public void aDimensionChangeWithoutAnIdAnticipatesItsScreen() {
        var loading = new BedrockLoadingScreenState();
        loading.startGame();
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        assertTrue(loading.accept(END_LOADING_SCREEN, null));
        // Geyser's ChangeDimensionPacket carries no loading screen id, and the client answers with none.
        loading.changeDimension(null, DIMENSION_SPAWN);
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        assertTrue(loading.active());
        assertTrue(loading.accept(END_LOADING_SCREEN, null));
        assertFalse(loading.active());
        assertFalse(loading.accept(START_LOADING_SCREEN, null));
        assertFalse(loading.active());
    }

    @Test
    public void dimensionChangesSentBackToBackAnticipateOneScreenEach() {
        var loading = new BedrockLoadingScreenState();
        loading.startGame();
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        assertTrue(loading.accept(END_LOADING_SCREEN, null));
        // A switch between two worlds of one environment goes through a temporary dimension.
        loading.changeDimension(null, DIMENSION_SPAWN);
        loading.changeDimension(null, DIMENSION_SPAWN);
        loading.changeDimension(7, DIMENSION_SPAWN);
        loading.changeDimension(7, DIMENSION_SPAWN);
        for (Integer id : new Integer[] {null, null, 7, 7}) {
            assertTrue(loading.accept(START_LOADING_SCREEN, id));
            assertTrue(loading.accept(END_LOADING_SCREEN, id));
        }
        assertFalse(loading.accept(START_LOADING_SCREEN, null));
        assertFalse(loading.accept(START_LOADING_SCREEN, 7));
    }

    @Test
    public void aDimensionChangeScreenPlacesTheActorAtItsPositionInOrder() {
        var loading = new BedrockLoadingScreenState();
        loading.startGame();
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        assertNull(loading.placement());
        assertTrue(loading.accept(END_LOADING_SCREEN, null));
        var first = new Vec3(0, 32767, 0);
        var second = new Vec3(4, 32767, -4);
        loading.changeDimension(null, first);
        loading.changeDimension(null, second);
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        assertEquals(first, loading.placement());
        assertTrue(loading.accept(END_LOADING_SCREEN, null));
        assertNull(loading.placement());
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        assertEquals(second, loading.placement());
    }

    @Test
    public void continuationKeepsTheDimensionChangePositions() {
        var previous = new BedrockLoadingScreenState();
        previous.changeDimension(7, new Vec3(1.5, 32767, -2.5));
        var continued = new BedrockLoadingScreenState();
        continued.restore(previous.continuation());
        assertTrue(continued.accept(START_LOADING_SCREEN, 7));
        assertEquals(new Vec3(1.5, 32767, -2.5), continued.placement());
    }

    @Test
    public void continuationKeepsEveryAnticipatedScreenOfAnId() {
        var previous = new BedrockLoadingScreenState();
        previous.changeDimension(null, DIMENSION_SPAWN);
        previous.changeDimension(null, DIMENSION_SPAWN);
        previous.changeDimension(7, DIMENSION_SPAWN);
        var continued = new BedrockLoadingScreenState();
        continued.restore(previous.continuation());
        assertTrue(continued.accept(START_LOADING_SCREEN, null));
        assertTrue(continued.accept(END_LOADING_SCREEN, null));
        assertTrue(continued.accept(START_LOADING_SCREEN, null));
        assertTrue(continued.accept(END_LOADING_SCREEN, null));
        assertFalse(continued.accept(START_LOADING_SCREEN, null));
        assertTrue(continued.accept(START_LOADING_SCREEN, 7));
        assertTrue(continued.accept(END_LOADING_SCREEN, 7));
        assertFalse(continued.accept(START_LOADING_SCREEN, 7));
    }

    @Test
    public void actorStaysAtTheDimensionSpawnUntilTheScreenWithoutAnIdEnds() {
        var loading = new BedrockLoadingScreenState();
        loading.startGame();
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        assertTrue(loading.accept(END_LOADING_SCREEN, null));
        // Capture of a hop into the End through Geyser: the client starts the screen before auth 22483, reports
        // no velocity at the spawn through auth 22494, ends the screen before auth 22495, still reports no
        // velocity on 22495 and reports its first gravity tick on 22496.
        loading.changeDimension(null, DIMENSION_SPAWN);
        assertTrue(loading.accept(START_LOADING_SCREEN, null));
        var spawn = new Vec3d(289, 82, -86);
        var state = BedrockMovementState.fromPhysicalFeet(
                        spawn, Vec3d.ZERO, BedrockInputFrame.idle(22482), BedrockCollisionFlags.AIR)
                .withTeleportPending();
        for (int tick = 22483; tick <= 22494; tick++) {
            state = advance(state, tick, loading);
            assertEquals(spawn, state.physicalFeetPosition());
            assertEquals(Vec3d.ZERO, state.velocity());
        }
        assertTrue(loading.accept(END_LOADING_SCREEN, null));
        state = advance(state, 22495, loading);
        assertEquals(spawn, state.physicalFeetPosition());
        assertEquals(Vec3d.ZERO, state.velocity());
        state = advance(state, 22496, loading);
        assertEquals(-0.0784F, state.velocity().y(), 1e-8);
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
