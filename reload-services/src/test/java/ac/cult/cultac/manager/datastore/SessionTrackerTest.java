package ac.cult.cultac.manager.datastore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.grim.grimac.api.storage.DataStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTrackerTest {
    @Test
    void oldDisconnectAndBrandCannotCloseOrReplaceReconnectedSession() {
        var tracker = new SessionTrackerImpl(mock(DataStore.class), 100);
        UUID player = UUID.randomUUID();
        var oldMeta = new SessionTracker.ClientMeta("1@abc", "old", 1, "test", UUID.randomUUID());
        var newMeta = new SessionTracker.ClientMeta("2@def", "new", 1, "test", UUID.randomUUID());
        UUID oldSession = tracker.observeActivity(player, 1000, oldMeta);
        UUID newSession = tracker.observeActivity(player, 2000, newMeta);
        assertNotEquals(oldSession, newSession);
        assertEquals(oldSession, tracker.currentSessionId(player, oldMeta));
        assertEquals(newSession, tracker.currentSessionId(player, newMeta));
        tracker.observeActivity(player, 2100, oldMeta);
        assertEquals(newSession, tracker.currentSessionId(player));
        tracker.close(player, 2200, oldMeta);
        assertNull(tracker.currentSessionId(player, oldMeta));
        assertEquals(newSession, tracker.currentSessionId(player));
        tracker.pollHeartbeat(player, oldMeta.connectionId(), 2300);
        assertEquals(newSession, tracker.currentSessionId(player, newMeta));
        tracker.close(player, 2400, newMeta);
        assertNull(tracker.currentSessionId(player));
    }

    @Test
    void existingCallersWithoutConnectionTokensRetainSessionSemantics() {
        var tracker = new SessionTrackerImpl(mock(DataStore.class), 100);
        UUID player = UUID.randomUUID();
        UUID first = tracker.observeActivity(player, 1000, SessionTracker.ClientMeta.empty());
        assertEquals(first, tracker.observeActivity(player, 2000, SessionTracker.ClientMeta.empty()));
        tracker.close(player, 3000, SessionTracker.ClientMeta.empty());
        assertNull(tracker.currentSessionId(player));
        assertNotEquals(first, tracker.observeActivity(player, 4000, SessionTracker.ClientMeta.empty()));
    }
}
