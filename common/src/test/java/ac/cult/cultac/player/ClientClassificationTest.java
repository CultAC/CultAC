package ac.cult.cultac.player;

import static ac.cult.cultac.player.ClientClassification.Type.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClientClassificationTest {
    @Test
    void brandNames() {
        String[] brands = {
            "vanilla",
            "fabric",
            "forge",
            "neoforge",
            "quilt",
            "lunarclient:v2.23.5-2643",
            "badlion",
            "feather",
            "labymod",
            "custom"
        };
        var types = List.of(VANILLA, FABRIC, FORGE, NEOFORGE, QUILT, LUNAR, BADLION, FEATHER, LABYMOD, UNKNOWN);
        for (int i = 0; i < brands.length; i++) assertEquals(types.get(i), ClientClassification.brandType(brands[i]));
    }

    @Test
    void publicChannelIdentifiers() {
        assertEquals(FABRIC, ClientClassification.channelType(List.of("fabric:registry/sync/direct")));
        assertEquals(FORGE, ClientClassification.channelType(List.of("fml:handshake", "forge:main")));
        assertEquals(FORGE, ClientClassification.channelType(List.of("FML|HS", "FML", "FORGE")));
        assertEquals(NEOFORGE, ClientClassification.channelType(List.of("neoforge:register")));
        assertEquals(LUNAR, ClientClassification.channelType(List.of("lunar:apollo", "lunarclient:pm")));
        assertEquals(BADLION, ClientClassification.channelType(List.of("badlion:mods")));
        assertEquals(LABYMOD, ClientClassification.channelType(List.of("labymod3:main")));
        assertEquals(UNSET, ClientClassification.channelType(List.of("c:register", "minecraft:brand")));
    }

    @Test
    void missingAndSharedChannelsDoNotContradictBrands() {
        for (var type : List.of(VANILLA, FABRIC, FORGE, NEOFORGE, QUILT, LUNAR, BADLION, FEATHER, BEDROCK)) {
            assertFalse(new ClientClassification(type, UNSET, UNSET).contradictsVanilla());
        }
        assertEquals(UNKNOWN, ClientClassification.channelType(List.of("lunar:apollo", "fabric:registry/sync/direct")));
    }

    @Test
    void displayOmitsMatchingChannelType() {
        assertEquals("brandtype=FABRIC", new ClientClassification(FABRIC, FABRIC, UNSET).display());
        assertEquals(
                "brandtype=UNKNOWN channeltype=UNSET modtype=LUNAR",
                new ClientClassification(UNKNOWN, UNSET, LUNAR).display());
        assertTrue(new ClientClassification(VANILLA, FABRIC, UNSET).contradictsVanilla());
    }
}
