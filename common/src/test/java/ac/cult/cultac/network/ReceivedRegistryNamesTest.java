package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.codec.WireRegistryState;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceivedRegistryNamesTest {
    @Test
    void splitModelContentsPublishTogetherAndDoNotModifyAnEarlierSnapshot() {
        var model = new ModelRegistryNamesState();
        var original = model.snapshot();
        model.append("minecraft:enchantment", List.of("test:first"));
        model.append("minecraft:enchantment", List.of("test:second"));
        assertSame(original, model.snapshot());
        model.finish();
        var published = model.snapshot();
        assertEquals(0, published.id("minecraft:enchantment", "test:first"));
        assertEquals(1, published.id("minecraft:enchantment", "test:second"));
        assertEquals(-1, original.id("minecraft:enchantment", "test:first"));
        assertFalse(published.contains("minecraft:worldgen/configured_feature"));
        model.finish();
        assertSame(published, model.snapshot(), "A tags-only finish keeps the model ID generation");
        model.append("minecraft:enchantment", List.of());
        model.finish();
        assertEquals(0, model.snapshot().size("minecraft:enchantment"));
        assertEquals(2, published.size("minecraft:enchantment"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> published.registries().clear());
    }

    @Test
    void sourceWireIdsAndPublishedModelIdsRemainSeparateDuringReconfiguration() {
        var model = new ModelRegistryNamesState();
        var wire = new WireRegistryState(ProtocolVersion.V1_21_3, model::snapshot);
        wire.append(new WireValueDecoder.RegistryValues(
                "minecraft:enchantment",
                List.of(
                        new WireValueDecoder.RegistryEntry("minecraft:efficiency", null),
                        new WireValueDecoder.RegistryEntry("minecraft:unbreaking", null))));
        assertEquals("minecraft:efficiency", wire.name("minecraft:enchantment", 0));
        int originalId = wire.modelId("minecraft:enchantment", "efficiency");
        assertTrue(originalId >= 0);
        wire.beginConfiguration();
        assertEquals("minecraft:efficiency", wire.name("minecraft:enchantment", 0));
        model.append("minecraft:enchantment", List.of("minecraft:unbreaking", "minecraft:efficiency"));
        model.finish();
        assertEquals(1, wire.modelId("minecraft:enchantment", "efficiency"));
        assertEquals("minecraft:efficiency", wire.name("minecraft:enchantment", 0));
    }
}
