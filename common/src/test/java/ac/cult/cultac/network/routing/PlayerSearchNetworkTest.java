package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting.SearchResult;
import ac.cult.cultac.network.PlayerSearchFilters;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlayerSearchNetworkTest {
    @Test
    void queryReadsPeerProfilesWithoutGrowingRoster() {
        AtomicLong clock = new AtomicLong(1_793_140_000_000L);
        TestNetworks networks = new TestNetworks(new FakeBroker(), clock);
        UUID alice = UUID.randomUUID();
        NetworkService here = networks.service("p1")
                .platform(new Players().with(UUID.randomUUID(), "Bob", "lobby", "vanilla"))
                .start();
        NetworkService peer = networks.service("p2")
                .platform(new Players().with(alice, "Alice", "game", "lunar"))
                .start();
        peer.searchDetails(uuid -> Map.of(
                "mod", List.of("Replay Mod", "Another Mod"), "sens", List.of("50"), "platform", List.of("java")));
        try {
            var result = here.search("mod=replay sens=50").join();
            assertEquals(1, result.total());
            assertEquals(0, result.unavailable());
            assertEquals(alice, result.players().getFirst().uuid());
            assertEquals(
                    "Replay Mod",
                    result.players().getFirst().details().get("mod").getFirst());
            assertTrue(here.roster().stream().allMatch(p -> p.details().isEmpty()));
            clock.addAndGet(2000);
            var combined = here.search("mod=replay mod=another").join();
            assertEquals(
                    "Replay Mod, Another Mod",
                    combined.players().getFirst().details().get("mod").getFirst());
            peer.searchDetails(uuid -> Map.of("platform", List.of("bedrock"), "sens", List.of("0")));
            clock.addAndGet(2000);
            assertEquals(0, here.search("sens=0").join().total());
        } finally {
            here.stop();
            peer.stop();
        }
    }

    @Test
    void currentGenerationUsesProfilesHeldByOlderGeneration() {
        AtomicLong clock = new AtomicLong(1_793_140_000_000L);
        TestNetworks networks = new TestNetworks(new FakeBroker(), clock);
        Map<String, Object> shared = new ConcurrentHashMap<>();
        UUID alice = UUID.randomUUID();
        Players players = new Players().with(alice, "Alice", "game", "vanilla");
        NetworkService old = networks.service("p1")
                .host(TestNetworks.host(shared, false))
                .platform(players)
                .start();
        NetworkService current = networks.service("p1")
                .host(TestNetworks.host(shared, true))
                .platform(players)
                .start();
        old.searchDetails(uuid -> Map.of("platform", List.of("java"), "sens", List.of("50")));
        try {
            assertEquals(1, current.search("sens=50").join().total());
        } finally {
            old.stop();
            current.stop();
        }
    }

    @Test
    void replyCodecPreservesPlainValuesAndNullServer() {
        NetworkPlayer player = new NetworkPlayer(
                UUID.randomUUID(),
                "Alice",
                "p2",
                null,
                "<red>brand",
                Map.of("channel", List.of("mod:channel"), "sens", List.of("50.1")));
        var encoded = PlayerSearchNetwork.encode("id", new SearchResult(List.of(player), 151, 0));
        var decoded = PlayerSearchNetwork.decode(
                "p2", JsonParser.parseString(encoded.toString()).getAsJsonObject());
        assertEquals(List.of(player), decoded.players());
        assertEquals(151, decoded.total());
    }

    @Test
    void cappedReplyFitsTheEnvelopeWithEscapedText() {
        Map<String, List<String>> details = new HashMap<>();
        for (int i = 0; i < 16; i++) details.put("key" + i, List.of("<".repeat(64)));
        List<NetworkPlayer> players = new ArrayList<>();
        for (int i = 0; i < 100; i++)
            players.add(new NetworkPlayer(UUID.randomUUID(), "Player" + i, "p2", "lobby", "<".repeat(64), details));
        String json = PlayerSearchNetwork.encode(UUID.randomUUID().toString(), new SearchResult(players, 200, 0))
                .toString();
        assertTrue(EnvelopeCodec.utf8Length(json) < EnvelopeCodec.MAX_BYTES - 1024);
    }

    @Test
    void capsRepliesWhileKeepingTotal() {
        AtomicLong clock = new AtomicLong(1_793_140_000_000L);
        TestNetworks networks = new TestNetworks(new FakeBroker(), clock);
        Players players = new Players();
        for (int i = 0; i < 125; i++) players.with(UUID.randomUUID(), "Player" + i, "lobby", "vanilla");
        NetworkService service = networks.service("p1").platform(players).start();
        try {
            var result = service.search("brand=vanilla").join();
            assertEquals(125, result.total());
            assertEquals(100, result.players().size());
        } finally {
            service.stop();
        }
    }

    @Test
    void unreachablePeerReportsIncompleteResults() {
        FakeBroker broker = new FakeBroker();
        TestNetworks networks = new TestNetworks(broker, new AtomicLong(1_793_140_000_000L));
        var transport = broker.transport();
        NetworkService here = networks.service("p1").platform(new Players()).start();
        NetworkService peer = networks.service("p2")
                .transport(transport)
                .platform(new Players().with(UUID.randomUUID(), "Alice", "game", "vanilla"))
                .start();
        try {
            transport.hold();
            var result = here.search("brand=vanilla").join();
            assertEquals(1, result.unavailable());
            assertEquals(0, result.total());
        } finally {
            transport.release();
            peer.stop();
            here.stop();
        }
    }

    @Test
    void rosterAndCompletionDoNotReadProfiles() {
        TestNetworks networks = new TestNetworks();
        NetworkService service = networks.service("p1")
                .platform(new Players().with(UUID.randomUUID(), "Alice", "lobby", "vanilla"))
                .start();
        service.searchDetails(uuid -> {
            throw new IllegalStateException("profile");
        });
        try {
            assertTrue(service.roster().stream().allMatch(p -> p.details().isEmpty()));
            assertFalse(
                    PlayerSearchFilters.suggestions(service.roster(), "brand=").isEmpty());
        } finally {
            service.stop();
        }
    }

    @Test
    void searchesShareAnAtomicAdmissionBudget() {
        TestNetworks networks = new TestNetworks();
        NetworkService service = networks.service("p1")
                .platform(new Players().with(UUID.randomUUID(), "Alice", "lobby", "vanilla"))
                .start();
        var captures = new AtomicInteger();
        service.searchDetails(uuid -> {
            captures.incrementAndGet();
            return Map.of();
        });
        try {
            var attempts = IntStream.range(0, 64)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> service.search("brand=vanilla")
                            .handle((result, error) -> null)
                            .join()))
                    .toList();
            attempts.forEach(CompletableFuture::join);
            assertEquals(1, captures.get());
            networks.advance(2000);
            for (int i = 0; i < 64; i++) service.search("brand=vanilla", UUID.randomUUID());
            assertEquals(9, captures.get());
        } finally {
            service.stop();
        }
    }

    @Test
    void incomingQueriesUsePeerAndSenderBudgets() {
        TestNetworks networks = new TestNetworks();
        NetworkService origin = networks.service("p1").platform(new Players()).start();
        NetworkService peer = networks.service("p2")
                .platform(new Players().with(UUID.randomUUID(), "Alice", "game", "vanilla"))
                .start();
        var captures = new AtomicInteger();
        peer.searchDetails(uuid -> {
            captures.incrementAndGet();
            return Map.of();
        });
        try {
            for (int i = 0; i < 64; i++) {
                var body = new JsonObject();
                body.addProperty("id", UUID.randomUUID().toString());
                body.addProperty("q", "brand=vanilla");
                body.addProperty("sender", UUID.randomUUID().toString());
                origin.send("p2", PlayerSearchNetwork.QUERY, body);
            }
            assertEquals(1, captures.get());
        } finally {
            origin.stop();
            peer.stop();
        }
    }

    @Test
    void exceptionsCompleteRequestsAndCancelDeadlines() throws Exception {
        FakeBroker broker = new FakeBroker();
        AtomicLong clock = new AtomicLong(1_793_140_000_000L);
        TestNetworks networks = new TestNetworks(broker, clock);
        var delegate = broker.transport();
        AtomicBoolean throwing = new AtomicBoolean();
        MessageTransport transport = new MessageTransport() {
            public String name() {
                return delegate.name();
            }

            public void start(List<String> channels, Receiver receiver) {
                delegate.start(channels, receiver);
            }

            public void publish(String channel, String text) {
                if (throwing.get()) throw new IllegalStateException("transport");
                delegate.publish(channel, text);
            }

            public void close() {
                delegate.close();
            }
        };
        NetworkService service = networks.service("p1")
                .transport(transport)
                .platform(new Players().with(UUID.randomUUID(), "Alice", "lobby", "vanilla"))
                .start();
        NetworkService peer = networks.service("p2")
                .platform(new Players().with(UUID.randomUUID(), "Bob", "game", "vanilla"))
                .start();
        var searchField = NetworkService.class.getDeclaredField("searches");
        searchField.setAccessible(true);
        PlayerSearchNetwork search = (PlayerSearchNetwork) searchField.get(service);
        try {
            var pendingField = PlayerSearchNetwork.class.getDeclaredField("pending");
            pendingField.setAccessible(true);
            var deadlinesField = PlayerSearchNetwork.class.getDeclaredField("deadlines");
            deadlinesField.setAccessible(true);
            for (int i = 0; i < 40; i++) {
                service.searchDetails(uuid -> {
                    throw new IllegalStateException("profile");
                });
                assertThrows(
                        CompletionException.class,
                        () -> search.search("brand=vanilla", UUID.randomUUID().toString())
                                .join());
                assertTrue(((Map<?, ?>) pendingField.get(search)).isEmpty());
                assertTrue(((ScheduledThreadPoolExecutor) deadlinesField.get(search))
                        .getQueue()
                        .isEmpty());
                clock.addAndGet(2000);
            }
            service.searchDetails(uuid -> Map.of());
            networks.tick(peer);
            throwing.set(true);
            assertThrows(
                    CompletionException.class,
                    () -> search.search("brand=vanilla", UUID.randomUUID().toString())
                            .join());
            assertTrue(((Map<?, ?>) pendingField.get(search)).isEmpty());
            assertTrue(((ScheduledThreadPoolExecutor) deadlinesField.get(search))
                    .getQueue()
                    .isEmpty());
        } finally {
            throwing.set(false);
            service.stop();
            peer.stop();
        }
    }
}
