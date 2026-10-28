package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RosterCacheTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final OnlinePlayer ALICE_ONLINE = new OnlinePlayer(ALICE, "Alice", "lobby");
    private static final OnlinePlayer BOB_ONLINE = new OnlinePlayer(BOB, "Bob", "lobby");

    private final RosterCache cache = new RosterCache();

    private static JsonObject full(List<OnlinePlayer> players, long... order) {
        return ordered(RosterCache.body(players, true, List.of(), new JsonObject()), order);
    }

    private static JsonObject delta(List<OnlinePlayer> players, List<UUID> left, long... order) {
        return ordered(RosterCache.body(players, false, left, new JsonObject()), order);
    }

    private static JsonObject ordered(JsonObject body, long... order) {
        if (order.length == 0) return body;
        JsonArray o = new JsonArray();
        o.add(order[0]);
        o.add(order[1]);
        body.add(RosterCache.ORDER, o);
        return body;
    }

    private List<String> names(long now) {
        return cache.players(now).stream().map(NetworkPlayer::name).sorted().toList();
    }

    @Test
    void aRosterMessageNotAfterTheLastFromItsInstanceIsDropped() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 5, 2), 0);
        cache.apply("p3", full(List.of(), 5, 1), 0);
        assertEquals(List.of("Bob"), names(0));
        cache.apply("p3", delta(List.of(), List.of(BOB), 5, 2), 0);
        assertEquals(List.of("Bob"), names(0));
        cache.apply("p3", delta(List.of(ALICE_ONLINE), List.of(), 5, 3), 0);
        assertEquals(List.of("Alice", "Bob"), names(0));
    }

    @Test
    void aBiggerEpochIsARestartAndAnOlderOneIsDropped() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 5, 100), 0);
        cache.apply("p3", full(List.of(ALICE_ONLINE), 6, 1), 0);
        assertEquals(List.of("Alice"), names(0));
        cache.apply("p3", full(List.of(BOB_ONLINE), 5, 200), 0);
        assertEquals(List.of("Alice"), names(0));
        cache.apply("p3", full(List.of(BOB_ONLINE), 6, 2), 0);
        assertEquals(List.of("Bob"), names(0));
    }

    @Test
    void aRosterMessageWithoutAnOrderIsAccepted() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 5, 2), 0);
        cache.apply("p3", full(List.of(ALICE_ONLINE)), 0);
        assertEquals(List.of("Alice"), names(0));
        cache.apply("p3", full(List.of(BOB_ONLINE), 5, 1), 0);
        assertEquals(List.of("Alice"), names(0));
    }

    @Test
    void aDroppedRosterMessageDoesNotCountAsHeard() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 5, 2), 0);
        cache.apply("p3", full(List.of(BOB_ONLINE), 5, 1), 40_000);
        assertEquals(0, cache.heardAt("p3", 40_000));
        assertEquals(List.of(), names(RosterCache.EXPIRY_MILLIS + 1));
    }

    @Test
    void eachInstanceIsOrderedOnItsOwn() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 5, 10), 0);
        cache.apply("p7", full(List.of(ALICE_ONLINE), 5, 1), 0);
        assertEquals(List.of("Alice", "Bob"), names(0));
    }

    @Test
    void aForgottenInstanceStartsOver() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 5, 10), 0);
        cache.expire(RosterCache.EXPIRY_MILLIS + 1);
        cache.apply("p3", full(List.of(ALICE_ONLINE), 5, 1), RosterCache.EXPIRY_MILLIS + 1);
        assertEquals(List.of("Alice"), names(RosterCache.EXPIRY_MILLIS + 1));
    }
}
