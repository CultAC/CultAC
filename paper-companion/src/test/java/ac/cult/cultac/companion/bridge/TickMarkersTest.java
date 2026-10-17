package ac.cult.cultac.companion.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Marker;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class TickMarkersTest {
    private final TickMarkers markers = new TickMarkers();

    @Test
    void answersAtTheEndOfTheTickThatReachedTheMarker() {
        QueuedSession session = new QueuedSession(true);
        markers.onMessage(session, new Marker(3));

        tickEnd();
        assertEquals(List.of(), session.sent);

        session.runQueued();
        assertEquals(List.of(), session.sent);
        tickEnd();
        assertEquals(List.of(new Marker(3)), session.sent);
        tickEnd();
        assertEquals(List.of(new Marker(3)), session.sent);
    }

    @Test
    void answersOnlyTheLatestMarkerReachedInATick() {
        QueuedSession first = new QueuedSession(true);
        QueuedSession second = new QueuedSession(true);
        markers.onMessage(first, new Marker(1));
        markers.onMessage(first, new Marker(2));
        markers.onMessage(second, new Marker(7));
        first.runQueued();
        second.runQueued();

        tickEnd();

        assertEquals(List.of(new Marker(2)), first.sent);
        assertEquals(List.of(new Marker(7)), second.sent);
    }

    @Test
    void aMarkerTheConnectionCannotQueueIsNeverAnswered() {
        QueuedSession session = new QueuedSession(false);
        markers.onMessage(session, new Marker(1));

        tickEnd();

        assertEquals(List.of(), session.sent);
    }

    private void tickEnd() {
        markers.onTickEnd(new ServerTickEndEvent(1, 50, 0));
    }

    private static final class QueuedSession implements CompanionSession {
        private final List<Runnable> queued = new ArrayList<>();
        private final List<BridgeMessage> sent = new ArrayList<>();
        private final boolean accepts;

        QueuedSession(boolean accepts) {
            this.accepts = accepts;
        }

        void runQueued() {
            queued.forEach(Runnable::run);
            queued.clear();
        }

        @Override
        public UUID playerId() {
            return UUID.randomUUID();
        }

        @Override
        public Player player() {
            return null;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void send(BridgeMessage message) {
            sent.add(message);
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public boolean runInPacketOrder(Runnable task) {
            return accepts && queued.add(task);
        }
    }
}
