package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientBrandTextTest {
    @Test
    void controlCharactersAreDropped() {
        assertEquals("Lunar Client", ClientBrandText.sanitize("Lu\u0000nar\n Cli\u001bent\u007f"));
        char rightToLeftOverride = 0x202E;
        char zeroWidthSpace = 0x200B;
        assertEquals("fabric", ClientBrandText.sanitize("fab" + rightToLeftOverride + "ric" + zeroWidthSpace));
    }

    @Test
    void colourCodesAreDropped() {
        assertEquals("vanilla", ClientBrandText.sanitize("§cvan§lilla§"));
        assertEquals("&cforge <red>", ClientBrandText.sanitize("&cforge <red>"));
        assertEquals("x", ClientBrandText.sanitize("§\u0000xx"));
    }

    @Test
    void theTextIsStrippedAndCutTo64Characters() {
        assertEquals("forge", ClientBrandText.sanitize("  forge\t "));
        String cut = ClientBrandText.sanitize("a".repeat(63) + " " + "b".repeat(10));
        assertEquals("a".repeat(63), cut);
        assertEquals(64, ClientBrandText.sanitize("c".repeat(100)).length());
        assertEquals("d".repeat(63), ClientBrandText.sanitize("d".repeat(63) + Character.toString(0x1F600)));
    }

    @Test
    void nothingLeftIsUnknown() {
        assertNull(ClientBrandText.sanitize(null));
        assertNull(ClientBrandText.sanitize(""));
        assertNull(ClientBrandText.sanitize(" \u0000 §a§l "));
    }

    @Test
    void theRecordsHoldTheSanitizedBrand() {
        assertEquals("vanilla", new NetworkPlayer(UUID.randomUUID(), "Alice", "p3", null, "§avanilla\u0007").brand());
    }
}
