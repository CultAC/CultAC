package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry;
import ac.cult.cultac.protocol.WireValueDecoder.RegistryValues;
import java.util.List;
import org.junit.jupiter.api.Test;

class WireRegistryGenerationTest {
    private static final String BIOME = "minecraft:worldgen/biome";

    @Test
    void anAppendThatFailsAfterClearingReportsTheClearAndKeepsTheGeneration() {
        var wire = new WireRegistryState(ProtocolVersion.V1_21_3, () -> null);
        wire.append(new RegistryValues(BIOME, List.of(new RegistryEntry("test:first", null))));
        wire.beginConfiguration();
        Object generation = wire.generation();
        Object emptied = wire.emptied();
        var unnamed = new RegistryValues(BIOME, List.of(new RegistryEntry(null, null)));
        assertThrows(NullPointerException.class, () -> wire.append(unnamed));
        assertNotSame(emptied, wire.emptied());
        assertSame(generation, wire.generation());
    }
}
