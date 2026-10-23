package ac.cult.cultac.cloud.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void cleanIsEveryCheckNegativeWithoutASpan() {
        LabelRequest request = LabelRequest.clean("Extreme", " 30 minutes watched ");
        assertEquals(new LabelRequest("all", "extreme", null, "30 minutes watched"), request);
        assertTrue(request.clean());
        JsonObject body = request.body(PLAYER, NOW, "Mod", MOD);
        assertEquals("all", body.get("cheat").getAsString());
        assertEquals("negative", body.get("value").getAsString());
        assertEquals("extreme", body.get("confidence").getAsString());
        assertFalse(body.has("fromEpochMillis"));
        assertFalse(body.has("toEpochMillis"));
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
                LabelRequest.cheat("other", "high", "30d", null)
                        .body(PLAYER, NOW, "Mod", MOD)
                        .get("fromEpochMillis")
                        .getAsLong());
    }

    @Test
    void rejectsWhatTheCommandDoesNotOffer() {
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("speed", "high", "24h", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("aimassist", "high", "24h", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("inventory", "high", "24h", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("other", "confirmed", "24h", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("other", "high", "day", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.cheat("other", "high", "90d", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.clean("confirmed", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> LabelRequest.cheat("other", "high", "24h", "x".repeat(LabelRequest.MAX_NOTE + 1)));
    }

    @Test
    void theCommandsBodiesAreUnchanged() {
        assertEquals(
                "{\"playerId\":\"6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b\","
                        + "\"cheat\":\"all\",\"value\":\"negative\",\"source\":\"staff\","
                        + "\"confidence\":\"extreme\",\"lookedAtBecause\":\"staff\",\"evidenceIndependent\":false,"
                        + "\"labeller\":\"Mod\",\"labellerId\":\"0f0e0d0c-0b0a-4908-8706-050403020100\","
                        + "\"evidence\":\"in-game label by Mod\"}",
                LabelRequest.clean("extreme", null)
                        .body(PLAYER, NOW, "Mod", MOD)
                        .toString());
        assertEquals(
                "{\"playerId\":\"6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b\",\"fromEpochMillis\":34473600000,"
                        + "\"toEpochMillis\":34560000000,\"cheat\":\"triggerbot\",\"value\":\"positive\","
                        + "\"source\":\"staff\",\"confidence\":\"high\",\"lookedAtBecause\":\"staff\","
                        + "\"evidenceIndependent\":false,\"labeller\":\"Mod\","
                        + "\"labellerId\":\"0f0e0d0c-0b0a-4908-8706-050403020100\",\"evidence\":\"seen\"}",
                LabelRequest.cheat("triggerbot", "high", "24h", "seen")
                        .body(PLAYER, NOW, "Mod", MOD)
                        .toString());
    }

    @Test
    void aWindowLabelCarriesItsOwnSpan() {
        LabelRequest.Window window = LabelRequest.Window.cheat("KillAura", "Extreme", NOW - 5 * DAY, NOW, " n ");
        JsonObject body = window.body(PLAYER, "Mod", MOD);
        assertEquals(NOW - 5 * DAY, body.get("fromEpochMillis").getAsLong());
        assertEquals(NOW, body.get("toEpochMillis").getAsLong());
        assertEquals("killaura", body.get("cheat").getAsString());
        assertEquals(
                "chest-stealer",
                LabelRequest.Window.cheat("Chest-Stealer", "high", NOW - DAY, NOW, null)
                        .body(PLAYER, "Mod", MOD)
                        .get("cheat")
                        .getAsString());
        assertEquals("positive", body.get("value").getAsString());
        assertEquals("extreme", body.get("confidence").getAsString());
        assertEquals("n", body.get("evidence").getAsString());
        assertEquals(MOD.toString(), body.get("labellerId").getAsString());

        JsonObject clean = LabelRequest.Window.clean("high", null).body(PLAYER, "Mod", MOD);
        assertEquals("all", clean.get("cheat").getAsString());
        assertEquals("negative", clean.get("value").getAsString());
        assertEquals("high", clean.get("confidence").getAsString());
        assertEquals("in-game label by Mod", clean.get("evidence").getAsString());
        assertFalse(clean.has("fromEpochMillis"));
        assertFalse(clean.has("toEpochMillis"));
        assertEquals(LabelRequest.clean("high", null).body(PLAYER, NOW, "Mod", MOD), clean);
    }

    @Test
    void aSuspectedLabelHasNoConfidence() {
        LabelRequest.Window window = LabelRequest.Window.suspected("autoclicker", NOW - DAY, NOW, null);
        assertNull(window.confidence());
        JsonObject body = window.body(PLAYER, "Mod", MOD);
        assertEquals("suspected", body.get("value").getAsString());
        assertEquals("autoclicker", body.get("cheat").getAsString());
        assertFalse(body.has("confidence"));
        assertEquals("staff", body.get("source").getAsString());
    }

    @Test
    void aWindowRejectsWhatTheMenuDoesNotOffer() {
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.Window.suspected("all", NOW - DAY, NOW, null));
        assertThrows(
                IllegalArgumentException.class, () -> LabelRequest.Window.cheat("speed", "high", NOW - DAY, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.Window.clean("confirmed", null));
        assertThrows(IllegalArgumentException.class, () -> LabelRequest.Window.cheat("other", "high", NOW, NOW, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> LabelRequest.Window.cheat(
                        "other", "high", NOW - DAY, NOW, "x".repeat(LabelRequest.MAX_NOTE + 1)));
    }
}
