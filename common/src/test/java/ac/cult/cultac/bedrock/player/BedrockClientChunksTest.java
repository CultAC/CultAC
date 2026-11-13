package ac.cult.cultac.bedrock.player;

import static ac.cult.cultac.bedrock.player.BedrockClientChunks.Hold.EITHER;
import static ac.cult.cultac.bedrock.player.BedrockClientChunks.Hold.FREE;
import static ac.cult.cultac.bedrock.player.BedrockClientChunks.Hold.HELD;
import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import org.junit.Test;

public class BedrockClientChunksTest {
    private static BedrockClientChunks.Hold hold(BedrockClientChunks chunks, double x, double z, long tick) {
        return chunks.hold(BedrockCoordinateFrame.IDENTITY, x, z, tick);
    }

    @Test
    public void aViewWithoutPublisherUpdateHoldsEveryColumn() {
        assertEquals(FREE, hold(new BedrockClientChunks(), 21000.5, 21000.5, 0));
    }

    // A teleport 1000 blocks away: the publisher update and the column are processed in the tick after 61919. The
    // client commits the column on a worker thread one to three ticks after the one that answered its marker.
    @Test
    public void aColumnMayHoldFromTheTickAfterItsMarkerUntilTheThirdTick() {
        var chunks = new BedrockClientChunks();
        chunks.publish(20073, 20273, 256);
        assertTrue(chunks.receive(20073 >> 4, 20273 >> 4, 61000));
        assertEquals(FREE, hold(chunks, 20073.5, 20273.5, 61917));
        assertEquals(HELD, hold(chunks, 21000.5, 21000.5, 61919));

        chunks.publish(21000, 21000, 256);
        assertTrue(chunks.receive(21000 >> 4, 21000 >> 4, 61919));
        assertEquals(HELD, hold(chunks, 21000.5, 21000.5, 61919));
        assertEquals(EITHER, hold(chunks, 21000.5, 21000.5, 61920));
        assertEquals(EITHER, hold(chunks, 21000.5, 21000.5, 61921));
        assertEquals(FREE, hold(chunks, 21000.5, 21000.5, 61922));
        // The move dropped the old column, which a later update around it does not bring back.
        chunks.publish(20073, 20273, 256);
        assertEquals(HELD, hold(chunks, 20073.5, 20273.5, 62000));
    }

    // Columns commit in the order they arrived: a move commits its column and every earlier one, a hold leaves its
    // column and every later one uncommitted.
    @Test
    public void aMoveOrAHoldNarrowsTheColumnsBeforeOrAfterIt() {
        var chunks = new BedrockClientChunks();
        chunks.publish(32, 0, 80);
        chunks.receive(0, 0, 100);
        chunks.receive(1, 0, 100);
        chunks.receive(2, 0, 100);
        assertEquals(EITHER, hold(chunks, 40, 8, 101));
        chunks.held(BedrockCoordinateFrame.IDENTITY, 24, 8, 101);
        assertEquals(EITHER, hold(chunks, 8, 8, 101));
        assertEquals(HELD, hold(chunks, 40, 8, 101));
        assertEquals(EITHER, hold(chunks, 40, 8, 102));
        chunks.moved(BedrockCoordinateFrame.IDENTITY, 24, 8, 102);
        assertEquals(FREE, hold(chunks, 8, 8, 102));
        assertEquals(FREE, hold(chunks, 24, 8, 102));
        assertEquals(EITHER, hold(chunks, 40, 8, 102));
    }

    @Test
    public void theViewIsTheSquareOfColumnsTheRadiusSpans() {
        var chunks = new BedrockClientChunks();
        chunks.publish(952, 744, 80);
        // (952 - 80) >> 4 = 54 and (952 + 80) >> 4 = 64; (744 - 80) >> 4 = 41 and (744 + 80) >> 4 = 51.
        assertTrue(chunks.receive(54, 41, 0));
        assertTrue(chunks.receive(64, 51, 0));
        assertFalse(chunks.receive(65, 46, 0));
        assertFalse(chunks.receive(59, 40, 0));
        assertFalse(chunks.receive(64, 51, 0));
    }

    // The client looks only at the column under the floor of its position, not at every column its box spans.
    @Test
    public void onlyTheColumnUnderThePositionHolds() {
        var chunks = new BedrockClientChunks();
        chunks.publish(16, 0, 80);
        chunks.receive(0, 0, 0);
        assertEquals(FREE, hold(chunks, 15.9, 8, 10));
        assertEquals(HELD, hold(chunks, 16.1, 8, 10));
    }

    // The client floors its float position in the shifted frame it was sent.
    @Test
    public void theColumnIsTheOneUnderTheClientsFloatPosition() {
        var chunks = new BedrockClientChunks();
        var frame = new BedrockCoordinateFrame(16 * 1000, 0, 1);
        chunks.publish(16 * 1000, 0, 80);
        chunks.receive(1000, 0, 0);
        // 16015.9999999 is 15.9999999 in the frame, which rounds to the float 16.0: the next column.
        assertEquals(HELD, chunks.hold(frame, 16 * 1000 + 15.9999999, 8, 10));
        assertEquals(FREE, chunks.hold(frame, 16 * 1000 + 15.99, 8, 10));
    }

    @Test
    public void aNewLevelHoldsNoEarlierColumn() {
        var chunks = new BedrockClientChunks();
        chunks.publish(0, 0, 80);
        chunks.receive(0, 0, 0);
        chunks.clear();
        chunks.publish(0, 0, 80);
        assertEquals(HELD, hold(chunks, 8, 8, 10));
    }

    @Test
    public void aCopyHoldsTheWrittenColumnsFromAnyTick() {
        var written = new BedrockClientChunks();
        written.publish(0, 0, 80);
        assertTrue(written.written(0, 0));
        assertFalse(written.written(0, 0));
        var chunks = new BedrockClientChunks();
        chunks.replace(written.copy());
        assertEquals(FREE, hold(chunks, 8, 8, Long.MIN_VALUE + 1));
        assertEquals(HELD, hold(chunks, 24, 8, 0));
    }
}
