package ac.cult.cultac.protocol.data;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ProtocolVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ModelRegistryDataTest {
    // SHA-256 of every state then every registry entry ("key\tname", keys sorted), each line-terminated, as
    // decoded from the original one-row-per-ID tables. Compacting the resources must not change them.
    private static final Map<Integer, String> EXPANDED = Map.of(
            768, "fdb65f2d419f9204eeba252b9016e65cdb58aa31fe93841311a586ed2462528c",
            769, "4a0bc42fb4df33e81883cb61f6f1122a67d16958df7f55f222d225164de1aae3",
            770, "a2d399eef057dfd42a1fcb3d4c5d9eae0f2e380da0ee46c9cf16519eb8537d1b",
            771, "481ba4c5d6229a719191fde2e083732e601539097d43d6c525a77618ecfe572c",
            772, "853755d9a606a94ecfcde95d634f1db3c6a0028a9c78124112e8c752f1b18dcd",
            773, "fe8124953edaef1e0e5767187059eb34cd3b98da89d8b272240268beea7096ca",
            774, "629e209685745680679505c1d44dc8195853c1d7710e3effc79990739f7cbbfc",
            775, "9100434a74942044eedb7e138bd9563928b5f45695690d2c487c8285c9766a09",
            776, "84c448ca3726bb18d4e35ce06c617b30ec440fc152ac43311f36fc9637df1582",
            777, "d8f3444036add89a91d2d2622e1f2670e06b80919ca378516f36f0eac9d0c0d7");

    @Test
    void everyVersionDecodesToTheOriginalTables() throws Exception {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            ModelRegistryData data = ModelRegistryData.load(version);
            var text = new StringBuilder();
            data.blockStates().forEach(state -> text.append(state).append('\n'));
            for (String key : new TreeSet<>(data.registryKeys())) {
                data.registry(key)
                        .names()
                        .forEach(name ->
                                text.append(key).append('\t').append(name).append('\n'));
            }
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals(EXPANDED.get(version.protocol()), HexFormat.of().formatHex(digest), version.toString());
        }
    }
}
