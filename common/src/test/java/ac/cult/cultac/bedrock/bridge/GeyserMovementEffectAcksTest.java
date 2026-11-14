package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.player.BedrockClientChunks;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.UUID;
import org.junit.Test;

public final class GeyserMovementEffectAcksTest {
    @Test
    public void returnAfterAuthFrameKeepsTheNextTickAsTheFinalBoost() {
        var acks = new GeyserMovementEffectAcks();
        acks.auth(390);
        acks.watch(-1822115790);
        acks.auth(391);
        acks.latency(-1822115790000000L);
        acks.auth(392);
        assertEquals(391, acks.release(-1822115790, 392));
    }

    @Test
    public void returnBeforeAuthFrameKeepsThatTickAsTheFinalBoost() {
        var acks = new GeyserMovementEffectAcks();
        acks.auth(13341);
        acks.watch(-1650099081);
        acks.latency(-1650099081000000L);
        acks.auth(13342);
        assertEquals(13341, acks.release(-1650099081, 13342));
    }

    // A tick far past the client's own, sent with an input the model refuses, so only the wire carried it.
    private static final long REFUSED_TICK = Long.MAX_VALUE - 1_000;

    @Test
    public void aTickOnlyARefusedInputCarriedCannotKeepABoostPastItsStop() {
        var state = new BedrockPlayerState(new UUID(0, 1));
        var acks = new GeyserMovementEffectAcks();
        assertTrue(state.acceptMovementTick(100));
        acks.auth(100);
        state.movementEffects.setGlideBoost(1_000_000);
        acks.watch(-40000);
        acks.auth(REFUSED_TICK);
        acks.latency(-40000L * 1_000_000L);
        state.movementEffects.setGlideBoost(0, acks.release(-40000, state));

        int boosted = 0;
        for (long tick = 101; tick <= 300; tick++) {
            if (state.acceptMovementTick(tick) && state.movementEffects.glideBoost(frame(tick), true)) boosted++;
        }
        assertTrue("boosted ticks after the stop: " + boosted, boosted <= 1);
        // The client that goes on counting from that tick takes the stop like any other.
        assertTrue(state.acceptMovementTick(REFUSED_TICK + 1));
        assertTrue(state.movementEffects.glideBoost(frame(REFUSED_TICK + 1), true));
        assertTrue(state.acceptMovementTick(REFUSED_TICK + 2));
        assertFalse(state.movementEffects.glideBoost(frame(REFUSED_TICK + 2), true));
    }

    @Test
    public void aTickOnlyARefusedInputCarriedCannotKeepAColumnWithoutItsBlocks() {
        var state = new BedrockPlayerState(new UUID(0, 1));
        var acks = new GeyserMovementEffectAcks();
        assertTrue(state.acceptMovementTick(100));
        acks.auth(100);
        var chunks = state.clientChunks;
        chunks.publish(8, 8, 64);
        acks.watch(-40001);
        acks.auth(REFUSED_TICK);
        acks.latency(-40001L * 1_000_000L);
        chunks.receive(0, 0, acks.release(-40001, state));

        int held = 0;
        for (long tick = 101; tick <= 300; tick++) {
            if (!state.acceptMovementTick(tick)) continue;
            if (chunks.hold(BedrockCoordinateFrame.IDENTITY, 8, 8, tick) == BedrockClientChunks.Hold.HELD) held++;
        }
        assertEquals("ticks held in a column the client has", 0, held);
        // The client that goes on counting from that tick has the column from the usual tick on.
        assertTrue(state.acceptMovementTick(REFUSED_TICK + 1));
        assertNotEquals(
                BedrockClientChunks.Hold.HELD,
                chunks.hold(BedrockCoordinateFrame.IDENTITY, 8, 8, REFUSED_TICK + 1));
        assertEquals(
                BedrockClientChunks.Hold.FREE,
                chunks.hold(BedrockCoordinateFrame.IDENTITY, 8, 8, REFUSED_TICK + 3));
    }

    @Test
    public void anAnswerAfterAcceptedInputsLeavesTheNextInputsAccepted() {
        var state = new BedrockPlayerState(new UUID(0, 1));
        var acks = new GeyserMovementEffectAcks();
        state.movementEffects.setGlideBoost(1_000_000);
        for (long tick = 390; tick <= 391; tick++) {
            assertTrue(state.acceptMovementTick(tick));
            acks.auth(tick);
        }
        acks.watch(-40002);
        acks.latency(-40002L * 1_000_000L);
        // The marker's work runs once the model has taken a later input.
        assertTrue(state.acceptMovementTick(392));
        acks.auth(392);
        assertTrue(state.movementEffects.glideBoost(frame(392), true));
        state.movementEffects.setGlideBoost(0, acks.release(-40002, state));
        assertEquals(392, state.lastMovementTick());
        assertTrue(state.acceptMovementTick(393));
        assertFalse(state.movementEffects.glideBoost(frame(393), true));
    }

    private static BedrockAuthInputFrame frame(long tick) {
        return BedrockAuthInputFrame.builder(new UUID(0, 1)).clientTick(tick).build();
    }
}
