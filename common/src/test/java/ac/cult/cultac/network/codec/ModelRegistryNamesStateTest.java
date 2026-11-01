package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.data.IdTable;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelRegistryNamesStateTest {
    private static void assertFresh(RegistryNames generation) {
        var names = new HashMap<String, List<String>>();
        generation.registries().forEach((registry, table) -> names.put(registry, table.names()));
        var fresh = new RegistryNames(names);
        assertEquals(fresh.registries().keySet(), generation.registries().keySet());
        fresh.registries().forEach((registry, table) -> {
            IdTable actual = generation.registries().get(registry);
            assertEquals(table.names(), actual.names(), registry);
            for (int id = 0; id < table.size(); id++)
                assertEquals(id, actual.id(table.names().get(id)), registry);
            assertEquals(
                    assertThrows(MalformedPacketException.class, () -> table.name(-1))
                            .getMessage(),
                    assertThrows(MalformedPacketException.class, () -> actual.name(-1))
                            .getMessage(),
                    registry);
        });
    }

    private static Map<String, List<String>> expectedStaged(Map<String, List<String>> received) {
        var defaults = ClientWorldDefaults.defaults();
        var values = new HashMap<String, List<String>>();
        for (String registry : defaults.builtinRegistries()) values.put(registry, defaults.initialNames(registry));
        for (String registry : defaults.networkRegistries())
            values.put(registry, received.getOrDefault(registry, List.of()));
        return values;
    }

    private static void assertNames(Map<String, List<String>> expected, RegistryNames generation) {
        assertEquals(expected.keySet(), generation.registries().keySet());
        expected.forEach((registry, names) ->
                assertEquals(names, generation.registries().get(registry).names(), registry));
        assertFresh(generation);
    }

    private static Map<String, List<String>> receive(ModelRegistryNamesState state, int extraBiomes) {
        var defaults = ClientWorldDefaults.defaults();
        var received = new HashMap<String, List<String>>();
        for (String registry : defaults.networkRegistries()) {
            var entries = new ArrayList<>(defaults.initialNames(registry));
            if (registry.equals(ClientWorldRegistries.BIOMES))
                for (int i = 0; i < extraBiomes; i++) entries.add("cult:extra_" + i);
            state.append(registry, entries);
            received.put(registry, List.copyOf(entries));
        }
        return received;
    }

    @Test
    void everyGenerationAcrossServerSwitchesEqualsAFreshBuild() {
        var state = new ModelRegistryNamesState();
        assertFresh(state.snapshot());

        var first = receive(state, 0);
        var staged = state.staged(false);
        assertNames(expectedStaged(first), staged);
        state.publish(staged);

        var same = receive(state, 0);
        var again = state.staged(false);
        assertNames(expectedStaged(same), again);
        for (String registry : again.registries().keySet())
            assertSame(staged.registries().get(registry), again.registries().get(registry), registry);
        state.publish(again);

        var changed = receive(state, 3);
        var other = state.staged(false);
        assertNames(expectedStaged(changed), other);
        assertEquals(
                again.registries().get(ClientWorldRegistries.BIOMES).size() + 3,
                other.registries().get(ClientWorldRegistries.BIOMES).size());
        state.publish(other);

        assertSame(other, state.staged(false));
    }

    @Test
    void olderAndFallbackGenerationsEqualAFreshBuild() {
        var state = new ModelRegistryNamesState();
        receive(state, 2);
        var older = state.staged(true);
        assertFresh(older);
        assertSame(older, state.projecting());
        state.publish(older);

        receive(state, 0);
        assertFresh(state.staged(true));
        state.fallback();
        assertFresh(state.snapshot());
        state.fallback();
        assertFresh(state.snapshot());
    }
}
