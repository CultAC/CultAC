package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.codec.connection.PingCodec;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.Packets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PacketTypeIndexTest {
    private static PacketType<ClientboundPing> ping(String key) {
        return new PacketType<>(
                key,
                ClientboundPing.class,
                PacketDirection.CLIENTBOUND,
                Set.of(ConnectionPhase.PLAY),
                List.of("minecraft:ping"),
                ProtocolVersion.V1_21_3,
                new PingCodec());
    }

    /** Every catalog type, then a type sharing a catalog key and a type with a key of its own. */
    private static List<PacketType<?>> probes() {
        List<PacketType<?>> probes = new ArrayList<>(Packets.all());
        probes.add(ping("clientbound.ping"));
        probes.add(ping("foreign"));
        return probes;
    }

    @Test
    void everyTypeHasItsOwnIndex() {
        Set<Integer> indices = new HashSet<>();
        for (PacketType<?> type : probes()) {
            assertTrue(type.index() >= 0, type.key());
            assertTrue(indices.add(type.index()), type.key());
        }
    }

    @Test
    void runtimeMembershipAndSlotsMatchTheCatalogByIdentity() {
        List<PacketType<?>> all = Packets.all();
        List<PacketType<?>> partial = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 3) partial.add(all.get(i));
        for (ProtocolVersion version : ProtocolVersion.values()) {
            ProtocolData data = ProtocolData.load(version);
            for (List<PacketType<?>> catalog : List.of(all, partial)) {
                ProtocolRuntime runtime = ProtocolRuntime.create(data, catalog);
                assertEquals(catalog.size(), runtime.slotCount());
                for (PacketType<?> type : probes()) {
                    String label = version + " " + catalog.size() + " " + type.key() + "#" + type.index();
                    assertEquals(runtime.typeForKey(type.key()) == type, runtime.contains(type), label);
                    int slot = slotByIdentity(catalog, type);
                    if (slot >= 0) {
                        assertEquals(slot, runtime.slot(type), label);
                    } else {
                        var failure = assertThrows(IllegalArgumentException.class, () -> runtime.slot(type), label);
                        assertEquals("Foreign packet family: " + type, failure.getMessage(), label);
                    }
                }
            }
        }
    }

    @Test
    void tablesAnswerAsTheMapsTheyCopy() {
        List<PacketType<?>> all = Packets.all();
        Map<PacketType<?>, String> map = new HashMap<>();
        for (int i = 0; i < all.size(); i += 2) map.put(all.get(i), all.get(i).key());
        PacketTypeTable<String> table = PacketTypeTable.of(map);
        PacketTypeTable<String> empty = PacketTypeTable.of(Map.of());
        for (PacketType<?> type : probes()) {
            assertEquals(map.get(type), table.get(type), type.key());
            assertNull(empty.get(type), type.key());
            assertNull(PacketTypeTable.<String>empty().get(type), type.key());
        }
        assertThrows(NullPointerException.class, () -> table.get(null));
    }

    private static int slotByIdentity(List<PacketType<?>> catalog, PacketType<?> type) {
        for (int slot = 0; slot < catalog.size(); slot++) if (catalog.get(slot) == type) return slot;
        return -1;
    }
}
