package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.shaded.vialib.util.Key;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ComponentKeyNamesTest {
    private static PrivateCodecService decoder;

    @BeforeAll
    static void open() throws Exception {
        decoder = new PrivateCodecService();
    }

    @AfterAll
    static void close() {
        if (decoder != null) decoder.close();
    }

    /** A present component's patch, layout and encoding key name it by its stripped and by its full identifier. */
    @Test
    void strippedAndFullIdentifiersNamespaceAlike() {
        int keys = 0;
        for (ProtocolVersion version : ProtocolVersion.values()) {
            var registry = ModelRegistryData.load(version).registry("minecraft:data_component_type");
            for (int id = 0; id < registry.size(); id++) {
                var key = PrivateCodecService.types(version).structuredData().key(id);
                if (key == null) continue;
                String identifier = key.identifier();
                assertEquals(Key.namespaced(identifier), Key.namespaced(Key.stripMinecraftNamespace(identifier)),
                        identifier);
                keys++;
            }
        }
        assertTrue(keys > 500);
    }
}
