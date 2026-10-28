package ac.cult.cultac.cloud.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class LabelCatalogTest {
    /** A catalog unlike the bundled one, as the service would serve it. */
    public static LabelCatalog swapped() {
        return LabelCatalog.parse(object("{\"version\":\"0123456789abcdef\",\"cheats\":["
                + "{\"id\":\"killaura\",\"name\":\"Kill Aura\"},{\"id\":\"newcheat\",\"name\":\"New Cheat\"},"
                + "{\"id\":\"other\",\"name\":\"Other\"}],\"legacy\":[{\"id\":\"reach\",\"name\":\"Reach\"}]}"));
    }

    @AfterEach
    void restore() {
        LabelCatalog.use(LabelCatalog.bundled());
    }

    @Test
    void theBundledCatalogListsThePickableCheatsInOrderWithOtherLast() {
        LabelCatalog bundled = LabelCatalog.bundled();
        assertEquals("bundled", bundled.version());
        assertEquals(
                List.of(
                        "triggerbot",
                        "killaura",
                        "autoclicker",
                        "autototem",
                        "crystalaura",
                        "autoanchor",
                        "automace",
                        "shieldbreaker",
                        "autostunslam",
                        "jumpreset",
                        "chest-stealer",
                        "scaffold",
                        "latency-abuse",
                        "other"),
                bundled.ids());
        assertEquals(
                new LabelCatalog.Entry("autostunslam", "Auto Stun Slam"),
                bundled.cheats().get(8));
        assertEquals("Latency Abuse", bundled.name("latency-abuse"));
        assertEquals(bundled.ids(), LabelCatalog.current().ids());
    }

    @Test
    void legacyCheatsHaveNamesButAreNotPickable() {
        LabelCatalog bundled = LabelCatalog.bundled();
        assertEquals("Reach", bundled.name("reach"));
        assertEquals("Bad packets", bundled.name("badpackets"));
        assertFalse(bundled.pickable("reach"));
        assertFalse(bundled.ids().contains("crystal-aura"));
        assertTrue(bundled.pickable("other"));
        assertFalse(bundled.pickable("all"));
        assertEquals("speed", bundled.name("speed"));
    }

    @Test
    void aServiceAnswerWithUnknownFieldsParses() {
        LabelCatalog catalog = LabelCatalog.parse(object("{\"version\":\"9f86d081884c7d65\",\"generatedAt\":12,"
                + "\"cheats\":[{\"id\":\"KillAura\",\"name\":\"KillAura\",\"checks\":[\"AimA\"]},"
                + "{\"id\":\"other\",\"name\":\"Other\"}],\"legacy\":[{\"id\":\"reach\",\"name\":\"Reach\",\"x\":1}],"
                + "\"extra\":{\"a\":[1]}}"));
        assertEquals("9f86d081884c7d65", catalog.version());
        assertEquals(List.of("killaura", "other"), catalog.ids());
        assertEquals("Reach", catalog.name("reach"));
    }

    @Test
    void aBodyWithoutPickableCheatsOrOtherIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LabelCatalog.parse(object("{\"version\":\"v\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> LabelCatalog.parse(object("{\"cheats\":[],\"legacy\":[{\"id\":\"reach\",\"name\":\"Reach\"}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> LabelCatalog.parse(object("{\"cheats\":[{\"id\":\"killaura\",\"name\":\"KillAura\"}]}")));
        assertThrows(IllegalArgumentException.class, () -> LabelCatalog.parse(object("{\"cheats\":{\"other\":1}}")));
    }

    @Test
    void aBadAnswerKeepsTheCurrentCatalog() {
        LabelCatalog current = LabelCatalog.current();
        assertNull(LabelClient.catalog(new LabelClient.Reply(200, "{\"cheats\":[]}")));
        assertNull(LabelClient.catalog(new LabelClient.Reply(200, "[]")));
        assertNull(LabelClient.catalog(new LabelClient.Reply(200, "<html>")));
        assertNull(LabelClient.catalog(new LabelClient.Reply(404, "{\"detail\":\"not found\"}")));
        assertSame(current, LabelCatalog.current());
    }

    @Test
    void aSwappedCatalogIsTheCurrentOneUntilRestored() {
        LabelCatalog.use(swapped());
        assertEquals(
                List.of("killaura", "newcheat", "other"), LabelCatalog.current().ids());
        assertEquals("Kill Aura", LabelCatalog.current().name("killaura"));
        LabelCatalog.use(LabelCatalog.bundled());
        assertEquals("KillAura", LabelCatalog.current().name("killaura"));
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
