package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerSearchFiltersTest {
    private final NetworkPlayer java = new NetworkPlayer(
            UUID.randomUUID(),
            "Alice",
            "p1",
            "Lobby",
            "Fabric Loader",
            Map.of(
                    "platform",
                    List.of("java"),
                    "sens",
                    List.of("49.8"),
                    "ping",
                    List.of("120"),
                    "channel",
                    List.of("test:mod"),
                    "mod",
                    List.of("testmod Test Mod 1.0"),
                    "version",
                    List.of("1.21.4")));

    private boolean matches(NetworkPlayer player, String query) {
        return PlayerSearchFilters.parse(query).stream()
                .allMatch(f -> !f.matched(player).isEmpty());
    }

    @Test
    void combinesCaseInsensitiveTextWithNumbers() {
        assertTrue(matches(
                java, "BRAND=fabric lo server=LOBBY mod=Test Mod sens=50 ping=>100 version=21.4 channel=TEST:"));
        assertFalse(matches(java, "brand=fabric ping=<100"));
        assertFalse(matches(java, "mod=absent"));
    }

    @Test
    void numbersHaveInclusiveRangesStrictComparisonsAndHalfUnitTolerance() {
        assertTrue(matches(java, "ping=120 sens=49-50"));
        assertTrue(matches(java, "ping=120-120 sens=<50"));
        assertFalse(matches(java, "ping=>120"));
        assertFalse(matches(java, "ping=<120"));
        assertFalse(matches(java, "sens=49"));
    }

    @Test
    void bedrockAndUnknownSensitivityCannotMatchZero() {
        NetworkPlayer bedrock = new NetworkPlayer(
                UUID.randomUUID(),
                "Bedrock",
                "p2",
                null,
                null,
                Map.of("platform", List.of("bedrock"), "sens", List.of("0")));
        assertFalse(matches(bedrock, "sens=0"));
        assertFalse(matches(new NetworkPlayer(UUID.randomUUID(), "Unknown", "p1", null), "sens=0"));
        assertTrue(matches(bedrock, "platform=bedrock"));
    }

    @Test
    void rejectsMalformedAndUnsupportedFilters() {
        for (String query : List.of(
                "foo",
                "brand=",
                "ping=NaN",
                "ping=Infinity",
                "ping=>",
                "ping=4-1",
                "ping=-2",
                "brand=x bogus=y",
                "notakey=x")) {
            assertThrows(IllegalArgumentException.class, () -> PlayerSearchFilters.parse(query), query);
        }
    }

    @Test
    void completesKeysValuesAndSubsequentFilters() {
        assertTrue(PlayerSearchFilters.suggestions(List.of(java), "").contains("sens="));
        assertTrue(PlayerSearchFilters.suggestions(List.of(java), "brand=fabric channel=te")
                .contains("brand=fabric channel=test:mod"));
    }
}
