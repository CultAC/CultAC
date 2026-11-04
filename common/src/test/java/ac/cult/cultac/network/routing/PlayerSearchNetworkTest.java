package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting.SearchResult;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PlayerSearchNetworkTest {
    @Test
    void queryReadsPeerProfilesWithoutGrowingRoster() {
        TestNetworks networks = new TestNetworks();
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
            var combined = here.search("mod=replay mod=another").join();
            assertEquals(
                    "Replay Mod, Another Mod",
                    combined.players().getFirst().details().get("mod").getFirst());
            peer.searchDetails(uuid -> Map.of("platform", List.of("bedrock"), "sens", List.of("0")));
            assertEquals(0, here.search("sens=0").join().total());
        } finally {
            here.stop();
            peer.stop();
        }
    }

    @Test
    void currentGenerationUsesProfilesHeldByOlderGeneration() {
        TestNetworks networks = new TestNetworks();
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
        TestNetworks networks = new TestNetworks();
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
}
