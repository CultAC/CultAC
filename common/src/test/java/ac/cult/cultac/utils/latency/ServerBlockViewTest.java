package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.value.BlockPos;
import org.junit.jupiter.api.Test;

class ServerBlockViewTest {
    private static final long POS = BlockPos.asLong(17, 64, -3);

    @Test
    void theNewestSentStateWinsUntilItsOwnProofApplies() {
        var view = new ServerBlockView();
        long older = view.sent(POS, 1);
        long newer = view.sent(POS, 2);
        assertEquals(2, view.sentState(POS));
        // The older update's proof must not hide the newer, still unacknowledged value.
        view.applied(POS, older);
        assertEquals(2, view.sentState(POS));
        view.applied(POS, newer);
        assertEquals(ServerBlockView.UNKNOWN, view.sentState(POS));
    }

    @Test
    void aSentChunkSupersedesEarlierUpdatesAndIsUnknownUntilApplied() {
        var view = new ServerBlockView();
        long id = view.sent(POS, 5);
        view.columnSent(17 >> 4, -3 >> 4);
        assertEquals(ServerBlockView.UNKNOWN, view.sentState(POS));
        assertTrue(view.isUnknown(1, -1));
        assertFalse(view.isUnknown(0, -1));
        view.applied(POS, id);
        view.columnApplied(1, -1);
        assertFalse(view.isUnknown(1, -1));
    }

    @Test
    void aSentChunkSupersedesOnlyItsOwnColumn() {
        var view = new ServerBlockView();
        long neighbor = BlockPos.asLong(15, 64, -3);
        long superseded = view.sent(POS, 5);
        long kept = view.sent(neighbor, 6);
        view.columnSent(1, -1);
        assertEquals(6, view.sentState(neighbor));
        long resent = view.sent(POS, 7);
        // The superseded update's proof must not hide the value sent after the chunk.
        view.applied(POS, superseded);
        assertEquals(7, view.sentState(POS));
        view.applied(POS, resent);
        view.applied(neighbor, kept);
        assertEquals(ServerBlockView.UNKNOWN, view.sentState(POS));
        assertEquals(ServerBlockView.UNKNOWN, view.sentState(neighbor));
    }

    @Test
    void manySentChunksKeepUpdatesOfOtherColumnsAndLaterOnes() {
        var view = new ServerBlockView();
        long other = BlockPos.asLong(15, 64, -3);
        view.sent(POS, 5);
        view.sent(other, 6);
        view.columnSent(1, -1);
        view.sent(POS, 7);
        for (int chunkX = 100; chunkX < 400; chunkX++) view.columnSent(chunkX, 0);
        assertEquals(7, view.sentState(POS));
        assertEquals(6, view.sentState(other));
        view.columnSent(1, -1);
        for (int chunkX = 100; chunkX < 400; chunkX++) view.columnSent(chunkX, 1);
        assertEquals(ServerBlockView.UNKNOWN, view.sentState(POS));
        assertEquals(6, view.sentState(other));
    }

    @Test
    void aWorldChangeHidesEveryColumnUntilItApplies() {
        var view = new ServerBlockView();
        view.sent(POS, 5);
        view.worldChangeSent();
        assertTrue(view.isUnknown(42, 42));
        assertEquals(ServerBlockView.UNKNOWN, view.sentState(POS));
        view.worldChangeApplied();
        assertFalse(view.isUnknown(42, 42));
    }
}
