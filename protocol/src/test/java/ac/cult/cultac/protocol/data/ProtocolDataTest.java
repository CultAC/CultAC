package ac.cult.cultac.protocol.data;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolDataTest {
    @Test
    void everyPinnedVersionLoadsWithOwnedImmutableTables() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            ProtocolData data = ProtocolData.load(version);
            assertEquals(version, data.version());
            IdTable movement = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND);
            assertTrue(movement.id("minecraft:move_player_pos_rot") >= 0);
            assertEquals(-1, movement.id("minecraft:does_not_exist"));
            assertEquals(0, data.registry("minecraft:block").id("minecraft:air"));
            assertThrows(
                    UnsupportedOperationException.class, () -> movement.names().clear());
            assertThrows(
                    UnsupportedOperationException.class, () -> data.registries().clear());
        }
    }

    @Test
    void corruptOrIncompleteDataCannotInitialize() throws Exception {
        ProtocolVersion version = ProtocolVersion.V26_2;
        String original;
        try (var stream = ProtocolData.class.getResourceAsStream("/ac/cult/cultac/protocol/data/776/index.tsv")) {
            assertNotNull(stream);
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String changed : List.of(
                original.replace("cult-protocol\t1\t776", "cult-protocol\t2\t776"),
                original.replace("cult-protocol\t1\t776", "cult-protocol\t1\t774"),
                original.replace("packet\tplay\tserverbound\t0\t", "packet\tplay\tserverbound\t1\t"),
                original.replaceAll("(?m)^packet\\tlogin[^\\n]*\\n", ""),
                original.replaceAll("(?m)^registry\\tminecraft:entity_type[^\\n]*\\n", ""),
                original.replaceAll("(?m)^registry\\tminecraft:menu[^\\n]*\\n", ""),
                original + "unknown\tdata\n")) {
            assertThrows(
                    ProtocolResolutionException.class,
                    () -> ProtocolData.readIndex(version, new StringReader(changed)));
        }
        assertThrows(
                ProtocolResolutionException.class,
                () -> new IdTable("duplicate", List.of("minecraft:air", "minecraft:air")));
        assertEquals("minecraft:", new IdTable("synced", List.of("minecraft:")).name(0));
    }
}
