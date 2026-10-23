package ac.cult.cultac.protocol.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolResolutionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegistryNamesTest {
    private static final List<String> BLOCKS = List.of("minecraft:air", "minecraft:stone", "minecraft:dirt");
    private static final List<String> BIOMES = List.of("minecraft:plains", "minecraft:desert");

    /** Tables equal in every observable: keys, description, names, ids and the unknown-name answer. */
    public static void assertSameTables(RegistryNames expected, RegistryNames actual) {
        assertEquals(expected.registries().keySet(), actual.registries().keySet());
        expected.registries().forEach((registry, table) -> {
            IdTable other = actual.registries().get(registry);
            assertEquals(table.names(), other.names(), registry);
            assertEquals(table.size(), other.size(), registry);
            for (int id = 0; id < table.size(); id++) {
                assertEquals(id, other.id(table.names().get(id)), registry);
                assertEquals(table.name(id), other.name(id), registry);
            }
            assertEquals(table.id("minecraft:absent"), other.id("minecraft:absent"), registry);
            assertEquals(
                    assertThrows(MalformedPacketException.class, () -> table.name(-1))
                            .getMessage(),
                    assertThrows(MalformedPacketException.class, () -> other.name(-1))
                            .getMessage(),
                    registry);
        });
    }

    private static RegistryNames derived(Map<String, List<String>> names, RegistryNames previous) {
        var result = new RegistryNames(names, previous);
        assertSameTables(new RegistryNames(names), result);
        return result;
    }

    @Test
    void anUnchangedRegistrySharesItsTable() {
        var previous = new RegistryNames(Map.of("minecraft:block", BLOCKS, "minecraft:worldgen/biome", BIOMES));
        var next = derived(
                Map.of("minecraft:block", BLOCKS, "minecraft:worldgen/biome", new ArrayList<>(BIOMES)), previous);
        assertSame(
                previous.registries().get("minecraft:block"), next.registries().get("minecraft:block"));
        assertSame(
                previous.registries().get("minecraft:worldgen/biome"),
                next.registries().get("minecraft:worldgen/biome"));
    }

    @Test
    void aChangedRegistryIsBuiltAgain() {
        var previous = new RegistryNames(Map.of("minecraft:block", BLOCKS, "minecraft:worldgen/biome", BIOMES));
        var appended = new ArrayList<>(BIOMES);
        appended.add("minecraft:forest");
        var reordered = List.of("minecraft:stone", "minecraft:air", "minecraft:dirt");
        var next = derived(Map.of("minecraft:block", reordered, "minecraft:worldgen/biome", appended), previous);
        assertNotSame(
                previous.registries().get("minecraft:block"), next.registries().get("minecraft:block"));
        assertEquals(1, next.id("block", "minecraft:air"));
        assertEquals(2, next.id("worldgen/biome", "forest"));

        var truncated = derived(Map.of("minecraft:worldgen/biome", BIOMES.subList(0, 1)), next);
        assertEquals(-1, truncated.id("worldgen/biome", "desert"));
        derived(Map.of("minecraft:worldgen/biome", List.of()), next);
    }

    @Test
    void registriesComeAndGoWithTheNewNames() {
        var previous = new RegistryNames(Map.of("minecraft:block", BLOCKS));
        var next = derived(Map.of("minecraft:worldgen/biome", BIOMES), previous);
        assertEquals(false, next.contains("block"));
        var again = derived(Map.of("minecraft:block", BLOCKS, "minecraft:worldgen/biome", BIOMES), next);
        assertNotSame(
                previous.registries().get("minecraft:block"), again.registries().get("minecraft:block"));
        assertSame(
                next.registries().get("minecraft:worldgen/biome"),
                again.registries().get("minecraft:worldgen/biome"));
    }

    @Test
    void aRegistryUnderAnotherKeyIsNeverShared() {
        var previous = new RegistryNames(Map.of("block", BLOCKS));
        var next = derived(Map.of("minecraft:block", BLOCKS), previous);
        assertNotSame(previous.registries().get("block"), next.registries().get("minecraft:block"));
    }

    @Test
    void invalidNamesStillFailWithTheSameMessage() {
        var previous = new RegistryNames(Map.of("minecraft:block", BLOCKS));
        var names = new HashMap<String, List<String>>();
        names.put("minecraft:block", List.of("minecraft:air", "Stone"));
        assertEquals(
                assertThrows(ProtocolResolutionException.class, () -> new RegistryNames(names))
                        .getMessage(),
                assertThrows(ProtocolResolutionException.class, () -> new RegistryNames(names, previous))
                        .getMessage());
        names.put("minecraft:block", null);
        assertThrows(NullPointerException.class, () -> new RegistryNames(names, previous));
    }
}
