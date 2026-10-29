package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RosterCacheTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final OnlinePlayer ALICE_ONLINE = new OnlinePlayer(ALICE, "Alice", "lobby");
    private static final OnlinePlayer BOB_ONLINE = new OnlinePlayer(BOB, "Bob", null);

    private final RosterCache cache = new RosterCache();

    private static JsonObject full(List<OnlinePlayer> players, Long order) {
        return ordered(RosterCache.body(players, true, List.of(), false, "n"), order);
    }

    private static JsonObject delta(List<OnlinePlayer> players, List<UUID> left, Long order) {
        return ordered(RosterCache.body(players, false, left, false, "n"), order);
    }

    private static JsonObject ordered(JsonObject body, Long order) {
        if (order != null) body.addProperty(RosterCache.ORDER, order);
        return body;
    }

    private List<String> names(long now) {
        return cache.players(now).stream().map(NetworkPlayer::name).sorted().toList();
    }

    @Test
    void aRosterMessageNotAfterTheLastFromItsInstanceIsDropped() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 2L), 0);
        cache.apply("p3", full(List.of(), 1L), 0);
        assertEquals(List.of("Bob"), names(0));
        cache.apply("p3", delta(List.of(), List.of(BOB), 2L), 0);
        assertEquals(List.of("Bob"), names(0));
        cache.apply("p3", delta(List.of(ALICE_ONLINE), List.of(), 3L), 0);
        assertEquals(List.of("Alice", "Bob"), names(0));
    }

    @Test
    void aLowerNumberIsAcceptedOnceNothingFromTheInstanceWasAppliedForLongerThanAnEnvelopeLives() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 100L), 0);
        cache.heard("p3", RosterCache.EXPIRY_MILLIS);
        cache.apply("p3", full(List.of(ALICE_ONLINE), 1L), RosterCache.EXPIRY_MILLIS);
        assertEquals(List.of("Bob"), names(RosterCache.EXPIRY_MILLIS));
        cache.apply("p3", full(List.of(ALICE_ONLINE), 1L), RosterCache.EXPIRY_MILLIS + 1);
        assertEquals(List.of("Alice"), names(RosterCache.EXPIRY_MILLIS + 1));
        cache.apply("p3", full(List.of(BOB_ONLINE), 0L), RosterCache.EXPIRY_MILLIS + 2);
        assertEquals(List.of("Alice"), names(RosterCache.EXPIRY_MILLIS + 2));
    }

    @Test
    void aRosterMessageWithoutANumberIsAccepted() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 2L), 0);
        cache.apply("p3", full(List.of(ALICE_ONLINE), null), 0);
        assertEquals(List.of("Alice"), names(0));
        cache.apply("p3", full(List.of(BOB_ONLINE), 1L), 0);
        assertEquals(List.of("Alice"), names(0));
    }

    @Test
    void aDroppedRosterMessageDoesNotCountAsHeard() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 2L), 0);
        cache.apply("p3", full(List.of(BOB_ONLINE), 1L), 40_000);
        assertEquals(0, cache.heardAt("p3", 40_000));
        assertEquals(List.of(), names(RosterCache.EXPIRY_MILLIS + 1));
    }

    @Test
    void eachInstanceIsOrderedOnItsOwn() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 10L), 0);
        cache.apply("p7", full(List.of(ALICE_ONLINE), 1L), 0);
        assertEquals(List.of("Alice", "Bob"), names(0));
    }

    @Test
    void aForgottenInstanceStartsOver() {
        cache.apply("p3", full(List.of(BOB_ONLINE), 10L), 0);
        cache.expire(RosterCache.EXPIRY_MILLIS + 1);
        cache.apply("p3", full(List.of(ALICE_ONLINE), 1L), RosterCache.EXPIRY_MILLIS + 1);
        assertEquals(List.of("Alice"), names(RosterCache.EXPIRY_MILLIS + 1));
    }

    @Test
    void playersKeepTheirServerAndVerboseFollowsTheLastMessageWhileTheInstanceLives() {
        cache.apply("p3", RosterCache.body(List.of(ALICE_ONLINE, BOB_ONLINE), true, List.of(), true, "n"), 0);
        assertEquals(
                List.of(new NetworkPlayer(ALICE, "Alice", "p3", "lobby"), new NetworkPlayer(BOB, "Bob", "p3", null)),
                cache.players(0).stream()
                        .sorted((a, b) -> a.name().compareTo(b.name()))
                        .toList());
        assertTrue(cache.verbose(0));
        assertFalse(cache.verbose(RosterCache.EXPIRY_MILLIS + 1));
        cache.apply("p3", RosterCache.body(List.of(), false, List.of(), false, "n"), 1);
        assertFalse(cache.verbose(1));
    }
}
