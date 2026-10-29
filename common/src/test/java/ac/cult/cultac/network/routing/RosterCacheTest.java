package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
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

    private @Nullable String brand(UUID uuid, long now) {
        return cache.players(now).stream()
                .filter(player -> player.uuid().equals(uuid))
                .findFirst()
                .orElseThrow()
                .brand();
    }

    @Test
    void fullAndDeltaBodiesCarryTheKnownBrandsApart() {
        JsonObject first = full(List.of(new OnlinePlayer(ALICE, "Alice", "lobby", "lunarclient"), BOB_ONLINE), 1L);
        JsonObject brands = first.getAsJsonObject("brands");
        assertEquals(1, brands.size());
        assertEquals(
                List.of(ALICE.toString()),
                brands.getAsJsonArray("lunarclient").asList().stream()
                        .map(JsonElement::getAsString)
                        .toList());
        assertEquals(
                "Bob",
                first.getAsJsonObject("servers")
                        .getAsJsonObject("")
                        .get(BOB.toString())
                        .getAsString());
        cache.apply("p3", first, 0);
        assertEquals("lunarclient", brand(ALICE, 0));
        assertNull(brand(BOB, 0));

        cache.apply("p3", delta(List.of(new OnlinePlayer(BOB, "Bob", null, "fabric")), List.of(), 2L), 0);
        assertEquals("fabric", brand(BOB, 0));
        assertEquals("lunarclient", brand(ALICE, 0));
        JsonObject unknownAgain = delta(List.of(ALICE_ONLINE), List.of(), 3L);
        assertFalse(unknownAgain.has("brands"));
        cache.apply("p3", unknownAgain, 0);
        assertNull(brand(ALICE, 0));
    }

    @Test
    void brandsAreGroupedByBrandAndRoundTrip() {
        UUID carol = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        String longBrand = "x".repeat(64);
        JsonObject body = full(
                List.of(
                        new OnlinePlayer(ALICE, "Alice", "lobby", "vanilla"),
                        new OnlinePlayer(BOB, "Bob", null, longBrand),
                        new OnlinePlayer(carol, "Carol", "lobby", "vanilla")),
                1L);
        JsonObject brands = body.getAsJsonObject("brands");
        assertEquals(2, brands.size());
        assertEquals(
                List.of(ALICE.toString(), carol.toString()),
                brands.getAsJsonArray("vanilla").asList().stream()
                        .map(JsonElement::getAsString)
                        .toList());
        assertEquals(1, brands.getAsJsonArray(longBrand).size());

        cache.apply("p3", body, 0);
        assertEquals("vanilla", brand(ALICE, 0));
        assertEquals(longBrand, brand(BOB, 0));
        assertEquals("vanilla", brand(carol, 0));
    }

    @Test
    void aBodyWithoutBrandsParsesAsBeforeWithTheBrandsUnknown() {
        JsonObject body = full(List.of(new OnlinePlayer(ALICE, "Alice", "lobby", "vanilla"), BOB_ONLINE), 1L);
        body.remove("brands");
        cache.apply("p3", body, 0);
        assertEquals(List.of("Alice", "Bob"), names(0));
        assertNull(brand(ALICE, 0));
        assertEquals(
                "lobby",
                cache.players(0).stream()
                        .filter(player -> player.uuid().equals(ALICE))
                        .findFirst()
                        .orElseThrow()
                        .server());
    }

    @Test
    void aMalformedBrandsEntryIsSkippedAndTheRestApplies() {
        JsonObject body = full(List.of(ALICE_ONLINE, BOB_ONLINE), 1L);
        JsonObject brands = new JsonObject();
        brands.add("vanilla", new JsonObject());
        JsonArray fabric = new JsonArray();
        fabric.add("not-a-uuid");
        fabric.add(5);
        fabric.add(new JsonObject());
        fabric.add(BOB.toString());
        brands.add("§cfab\u0007ric", fabric);
        brands.addProperty("lunar", ALICE.toString());
        body.add("brands", brands);
        cache.apply("p3", body, 0);
        assertEquals(List.of("Alice", "Bob"), names(0));
        assertNull(brand(ALICE, 0));
        assertEquals("fabric", brand(BOB, 0));

        JsonObject notAnObject = full(List.of(ALICE_ONLINE), 2L);
        notAnObject.add("brands", new JsonArray());
        cache.apply("p3", notAnObject, 0);
        assertEquals(List.of("Alice"), names(0));
        JsonObject number = full(List.of(ALICE_ONLINE), 3L);
        JsonObject numbered = new JsonObject();
        numbered.addProperty("vanilla", 5);
        number.add("brands", numbered);
        cache.apply("p3", number, 0);
        assertNull(brand(ALICE, 0));
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
