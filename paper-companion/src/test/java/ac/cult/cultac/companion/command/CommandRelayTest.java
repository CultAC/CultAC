package ac.cult.cultac.companion.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bridge.protocol.BridgeMessage.Command;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandDone;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandOutput;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggest;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggestions;
import ac.cult.cultac.companion.FakeBridge;
import ac.cult.cultac.companion.FakeSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandRelayTest {
    private static final UUID STAFF = UUID.fromString("2b5f1e7a-6a39-4d63-9a3f-0f1e2d3c4b5a");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final FakeBridge bridge = new FakeBridge();
    private long now = 1_000;
    private final List<Component> heard = new ArrayList<>();
    private CommandRelay relay;

    @BeforeEach
    void setUp() {
        relay = new CommandRelay(bridge, () -> now, Logger.getLogger("relay-test"), false);
    }

    @Test
    void noSessionTellsTheSender() {
        CompletableFuture<Boolean> done = relay.forward(null, "CONSOLE", "cult reload", heard::add);

        assertEquals(List.of(CommandRelay.UNREACHABLE), heard);
        assertFalse(done.join());
    }

    @Test
    void consoleRidesAnySessionAsTheConsole() {
        FakeSession any = bridge.open(OTHER);

        relay.forward(null, "CONSOLE", "cult reload", heard::add);

        Command sent = assertInstanceOf(Command.class, any.sent.getFirst());
        assertEquals(Command.CONSOLE, sent.sender());
        assertEquals("CONSOLE", sent.senderName());
        assertEquals("cult reload", sent.line());
    }

    @Test
    void playerPrefersOwnSession() {
        FakeSession other = bridge.open(OTHER);
        FakeSession own = bridge.open(STAFF);

        relay.forward(STAFF, "Staff", "cult alerts", heard::add);

        assertTrue(other.sent.isEmpty());
        Command sent = assertInstanceOf(Command.class, own.sent.getFirst());
        assertEquals(STAFF, sent.sender());
    }

    @Test
    void playerWithoutSessionRidesAnother() {
        FakeSession other = bridge.open(OTHER);

        relay.forward(STAFF, "Staff", "cult alerts", heard::add);

        assertEquals(
                STAFF, assertInstanceOf(Command.class, other.sent.getFirst()).sender());
    }

    @Test
    void outputRelaysInOrderIncludingAfterDone() {
        FakeSession session = bridge.open(OTHER);
        CompletableFuture<Boolean> done = relay.forward(null, "CONSOLE", "cult history", heard::add);
        int id = ((Command) session.sent.getFirst()).requestId();

        relay.onMessage(session, output(id, "one"));
        relay.onMessage(session, output(id, "two"));
        relay.onMessage(session, new CommandDone(id));
        relay.onMessage(session, output(id, "late"));

        assertTrue(done.join());
        assertEquals(List.of(text("one"), text("two"), text("late")), heard);
    }

    @Test
    void expiryEndsLateOutputAfterDone() {
        FakeSession session = bridge.open(OTHER);
        relay.forward(null, "CONSOLE", "cult history", heard::add);
        int id = ((Command) session.sent.getFirst()).requestId();
        relay.onMessage(session, new CommandDone(id));

        now += CommandRelay.EXPIRY_MILLIS;
        relay.sweep();
        relay.onMessage(session, output(id, "too late"));

        assertTrue(heard.isEmpty());
    }

    @Test
    void playersCannotFillTheConsoleReserveOfFinishedRequests() {
        FakeSession session = bridge.open(OTHER);
        for (int i = 0; i < CommandRelay.MAX_PENDING; i++) {
            relay.forward(new UUID(3L, i), "alt", "cult", message -> {});
            relay.onMessage(session, new CommandDone(((Command) session.sent.getLast()).requestId()));
        }

        relay.forward(null, "CONSOLE", "cult history", heard::add);
        int id = ((Command) session.sent.getLast()).requestId();
        relay.onMessage(session, new CommandDone(id));
        relay.onMessage(session, output(id, "late"));

        assertEquals(List.of(text("late")), heard);
    }

    @Test
    void eachPlayerHoldsAFewRequests() {
        FakeSession session = bridge.open(OTHER);
        for (int i = 0; i < CommandRelay.MAX_PENDING_PER_PLAYER; i++) {
            relay.forward(STAFF, "Staff", "cult", message -> {});
        }

        assertFalse(relay.forward(STAFF, "Staff", "cult", heard::add).join());
        assertEquals(List.of(CommandRelay.BUSY), heard);

        CompletableFuture<Boolean> console = relay.forward(null, "CONSOLE", "cult", message -> {});
        CompletableFuture<Boolean> other = relay.forward(OTHER, "Other", "cult", message -> {});
        assertFalse(console.isDone());
        assertFalse(other.isDone());

        relay.onMessage(session, new CommandDone(((Command) session.sent.getFirst()).requestId()));
        assertFalse(relay.forward(STAFF, "Staff", "cult", message -> {}).isDone());
    }

    @Test
    void playersCannotTakeTheConsoleReserve() {
        bridge.open(OTHER);
        int players = (CommandRelay.MAX_PENDING - CommandRelay.CONSOLE_RESERVE) / CommandRelay.MAX_PENDING_PER_PLAYER;
        for (int p = 0; p < players; p++) {
            UUID alt = new UUID(1L, p);
            for (int i = 0; i < CommandRelay.MAX_PENDING_PER_PLAYER; i++) relay.forward(alt, "alt", "cult", m -> {});
        }

        assertFalse(relay.forward(new UUID(2L, 0L), "alt", "cult", heard::add).join());
        assertFalse(relay.forward(null, "CONSOLE", "cult", message -> {}).isDone());
    }

    @Test
    void expiryForgetsTheRequest() {
        FakeSession session = bridge.open(OTHER);
        CompletableFuture<Boolean> done = relay.forward(null, "CONSOLE", "cult history", heard::add);
        int id = ((Command) session.sent.getFirst()).requestId();

        now += CommandRelay.EXPIRY_MILLIS - 1;
        relay.sweep();
        assertFalse(done.isDone());

        now += 1;
        relay.sweep();
        relay.onMessage(session, output(id, "too late"));

        assertFalse(done.join());
        assertTrue(heard.isEmpty());
    }

    @Test
    void unknownIdsAndForeignSessionsAreDropped() {
        FakeSession session = bridge.open(OTHER);
        FakeSession foreign = bridge.open(STAFF);
        CompletableFuture<Boolean> done = relay.forward(null, "CONSOLE", "cult history", heard::add);
        int id = ((Command) session.sent.getFirst()).requestId();

        relay.onMessage(session, output(id + 1, "unknown"));
        relay.onMessage(foreign, output(id, "foreign"));
        relay.onMessage(foreign, new CommandDone(id));
        relay.onMessage(session, new CommandOutput(id, "{not json"));

        assertTrue(heard.isEmpty());
        assertFalse(done.isDone());
    }

    @Test
    void sessionCloseFailsItsRequests() {
        FakeSession session = bridge.open(OTHER);
        CompletableFuture<Boolean> done = relay.forward(null, "CONSOLE", "cult history", heard::add);

        relay.onClosed(session);

        assertFalse(done.join());
    }

    @Test
    void pendingRequestsAreBounded() {
        bridge.open(OTHER);
        for (int i = 0; i < CommandRelay.MAX_PENDING; i++) relay.forward(null, "CONSOLE", "cult", message -> {});

        CompletableFuture<Boolean> refused = relay.forward(null, "CONSOLE", "cult", heard::add);

        assertFalse(refused.join());
        assertEquals(List.of(CommandRelay.BUSY), heard);
    }

    @Test
    void consoleSuggestionsComeFromTheProxy() {
        FakeSession session = bridge.open(OTHER);
        session.onSend = message -> {
            if (message instanceof Suggest suggest) {
                relay.onMessage(session, new Suggestions(suggest.requestId(), List.of("alerts", "history")));
            }
        };

        assertEquals(List.of("alerts", "history"), relay.suggest("cult ", 200));
        Suggest sent = assertInstanceOf(Suggest.class, session.sent.getFirst());
        assertEquals(Command.CONSOLE, sent.sender());
        assertEquals("cult ", sent.line());
    }

    @Test
    void unansweredSuggestionsGiveUp() {
        bridge.open(OTHER);
        assertNull(relay.suggest("cult ", 1));
    }

    private static CommandOutput output(int id, String text) {
        return new CommandOutput(id, GsonComponentSerializer.gson().serialize(text(text)));
    }

    private static Component text(String text) {
        return Component.text(text);
    }
}
