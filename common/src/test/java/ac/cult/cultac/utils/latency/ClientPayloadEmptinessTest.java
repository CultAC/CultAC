package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientPayloadEmptinessTest {
    private static final List<String> REGISTRIES = List.of(
            "minecraft:block",
            "minecraft:item",
            "minecraft:fluid",
            "minecraft:entity_type",
            "minecraft:worldgen/biome");

    @Test
    void untranslatedPayloadsAreEmptyExactlyWhenTheirProjectionIs() {
        var named = new LinkedHashMap<String, List<Integer>>();
        named.put("logs", List.of(0, 1, 2));
        named.put("unknown_members", List.of(-1, Integer.MAX_VALUE));
        named.put("cleared", List.of());
        var payloads = List.of(
                RegistryTags.Payload.EMPTY,
                new RegistryTags.Payload(Map.of("cleared", List.of())),
                new RegistryTags.Payload(Map.of("unknown_members", List.of(-1, Integer.MAX_VALUE))),
                new RegistryTags.Payload(named));
        for (ProtocolVersion version : ProtocolVersion.values()) {
            for (String registry : REGISTRIES) {
                for (var payload : payloads) {
                    assertEquals(
                            ClientComponentRegistries.projectedPayloadIsEmpty(registry, payload, version, version),
                            ClientComponentRegistries.clientPayloadIsEmpty(registry, payload, version, version),
                            version + " " + registry + " " + payload);
                }
            }
        }
    }
}
