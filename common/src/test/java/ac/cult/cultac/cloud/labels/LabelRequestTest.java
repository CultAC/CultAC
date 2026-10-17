package ac.cult.cultac.cloud.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LabelRequestTest {
    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final UUID MOD = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final long DAY = 86_400_000L;
    private static final long NOW = 400 * DAY;

    @Test
    void cleanIsEveryCheckNegativeOverItsSpan() {
        LabelRequest request = LabelRequest.clean("Extreme", "7D", " 30 minutes watched ");
        assertEquals(new LabelRequest("all", "extreme", LabelRequest.Span.D7, "30 minutes watched"), request);
        assertTrue(request.clean());
        JsonObject body = request.body(PLAYER, NOW, "Mod", MOD);
        assertEquals("all", body.get("cheat").getAsString());
        assertEquals("negative", body.get("value").getAsString());
        assertEquals("extreme", body.get("confidence").getAsString());
        assertEquals(NOW - 7 * DAY, body.get("fromEpochMillis").getAsLong());
        assertEquals(NOW, body.get("toEpochMillis").getAsLong());
        assertEquals("30 minutes watched", body.get("evidence").getAsString());
        assertFalse(LabelRequest.CHEATS.contains("all"));
    }

    @Test
    void aCheatLabelIsAlwaysPositive() {
        LabelRequest request = LabelRequest.cheat("AutoTotem", "high", "24h", "note");
        JsonObject body = request.body(PLAYER, NOW, "Mod", MOD);

        assertEquals(PLAYER.toString(), body.get("playerId").getAsString());
        assertEquals(NOW - DAY, body.get("fromEpochMillis").getAsLong());
        assertEquals(NOW, body.get("toEpochMillis").getAsLong());
        assertEquals("autototem", body.get("cheat").getAsString());
        assertEquals("positive", body.get("value").getAsString());
        assertEquals("staff", body.get("source").getAsString());
        assertEquals("high", body.get("confidence").getAsString());
        assertEquals("staff", body.get("lookedAtBecause").getAsString());
        assertFalse(body.get("evidenceIndependent").getAsBoolean());
        assertEquals("Mod", body.get("labeller").getAsString());
        assertEquals(MOD.toString(), body.get("labellerId").getAsString());
        assertEquals("note", body.get("evidence").getAsString());
        assertEquals(
                "in-game label by Mod",
                LabelRequest.cheat("other", "high", "3d", null)
                        .body(PLAYER, NOW, "Mod", MOD)
                        .get("evidence")
                        .getAsString());
    }

    @Test
    void spansAre24hTo30d() {
        assertEquals(List.of("24h", "3d", "7d", "30d"), LabelRequest.Span.wires());
        assertEquals(
                NOW - 3 * DAY,
                LabelRequest.cheat("other", "high", "3d", null)
                        .body(PLAYER, NOW, "Mod", MOD)
                        .get("fromEpochMillis")
                        .getAsLong());
        assertEquals(
                NOW - 30 * DAY,
                LabelRequest.clean("high", "30d", null)
                        .body(PLAYER, NOW, "Mod", MOD)
                        .get("fromEpochMillis")
                        .getAsLong());
    }

    @Test
    void rejectsWhatTheCommandDoesNotOffer() {
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("speed", "high", "24h", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("other", "confirmed", "24h", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("other", "high", "day", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("other", "high", "90d", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.clean("high", "always", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> LabelRequest.cheat("other", "high", "24h", "x".repeat(LabelRequest.MAX_NOTE + 1)));
    }
}
