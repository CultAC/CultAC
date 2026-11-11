package ac.cult.cultac.bedrock.player;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import org.junit.Test;

public class BedrockClientChunksTest {
    private static WorldCollisionBox box(double x, double z) {
        return new WorldCollisionBox(x - 0.3, 150, z - 0.3, x + 0.3, 151.8, z + 0.3);
    }

    @Test
    public void aViewWithoutPublisherUpdateHoldsEveryColumn() {
        assertTrue(new BedrockClientChunks().holds(box(21000.5, 21000.5), 0));
    }

    // A teleport 1000 blocks away: the publisher update and the column are processed in one tick (61920), which
    // the client still spends in place; it moves again from the next tick (61921).
    @Test
    public void aColumnHoldsFromTheTickAfterTheOneThatProcessedIt() {
        var chunks = new BedrockClientChunks();
        chunks.publish(20073, 20273, 256);
        assertTrue(chunks.receive(20073 >> 4, 20273 >> 4, 61000));
        assertTrue(chunks.holds(box(20073.5, 20273.5), 61917));
        assertFalse(chunks.holds(box(21000.5, 21000.5), 61919));

        chunks.publish(21000, 21000, 256);
        assertTrue(chunks.receive(21000 >> 4, 21000 >> 4, 61919));
        assertFalse(chunks.holds(box(21000.5, 21000.5), 61920));
        assertTrue(chunks.holds(box(21000.5, 21000.5), 61921));
        // The move dropped the old column, which a later update around it does not bring back.
        chunks.publish(20073, 20273, 256);
        assertFalse(chunks.holds(box(20073.5, 20273.5), 62000));
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

    @Test
    public void aBoxAcrossColumnsNeedsEveryOne() {
        var chunks = new BedrockClientChunks();
        chunks.publish(16, 0, 80);
        chunks.receive(0, 0, 0);
        assertFalse(chunks.holds(box(15.9, 8), 10));
        chunks.receive(1, 0, 0);
        assertTrue(chunks.holds(box(15.9, 8), 10));
    }

    @Test
    public void aNewLevelHoldsNoEarlierColumn() {
        var chunks = new BedrockClientChunks();
        chunks.publish(0, 0, 80);
        chunks.receive(0, 0, 0);
        chunks.clear();
        chunks.publish(0, 0, 80);
        assertFalse(chunks.holds(box(8, 8), 10));
    }

    @Test
    public void aCopyHoldsTheWrittenColumnsFromAnyTick() {
        var written = new BedrockClientChunks();
        written.publish(0, 0, 80);
        assertTrue(written.written(0, 0));
        assertFalse(written.written(0, 0));
        var chunks = new BedrockClientChunks();
        chunks.replace(written.copy());
        assertTrue(chunks.holds(box(8, 8), Long.MIN_VALUE + 1));
        assertFalse(chunks.holds(box(24, 8), 0));
    }
}
