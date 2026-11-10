package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.blocksim.environment.DimensionData;
import ac.cult.cultac.network.packet.RegistryData;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** The last-answer memos of the registries answer exactly what a fresh lookup or build does after every write. */
class RegistryMemoTest {
    private static final List<String> DIMENSIONS =
            List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    @Test
    void environmentsMatchAFreshBuild() throws Exception {
        var initial = defaults();
        var worlds = new ClientWorldRegistries(initial);
        var timelines = initial.registries().get(ClientWorldRegistries.TIMELINES).size();
        var timelineTags = List.copyOf(new TreeSet<>(initial.tags().get(ClientWorldRegistries.TIMELINES).keySet()));
        var biomes = initial.registries().get(ClientWorldRegistries.BIOMES).size();
        var bindings = new ArrayList<DimensionData.Binding>();
        var random = new Random(17);
        int hits = 0, builds = 0, failures = 0;
        for (int step = 0; step < 1500; step++) {
            switch (random.nextInt(10)) {
                case 0, 1 -> bindings.add(worlds.dimension(DIMENSIONS.get(random.nextInt(DIMENSIONS.size()))));
                case 2 -> worlds.applyTags(ClientWorldRegistries.TIMELINES, payload(random, timelineTags, timelines));
                case 3 -> worlds.applyTags(
                        ClientWorldRegistries.BIOMES, payload(random, List.of("minecraft:is_overworld"), biomes));
                case 4 -> {
                    if (random.nextInt(4) == 0)
                        worlds.finish(knownPacks(initial), ProtocolVersion.V26_3, Map.of(
                                ClientWorldRegistries.TIMELINES, payload(random, timelineTags, timelines)),
                                true);
                    else worlds.finish(Map.of(), ProtocolVersion.V1_21_11, Map.of(
                            ClientWorldRegistries.TIMELINES, payload(random, timelineTags, timelines)),
                            false);
                }
                default -> {
                    if (bindings.isEmpty()) continue;
                    var binding = bindings.get(random.nextInt(bindings.size()));
                    var last = field(worlds, "lastEnvironment");
                    var memo = outcome(() -> binding.environment().get());
                    if (last != null && memo == field(last, "value")) hits++;
                    setField(worlds, "lastEnvironment", null);
                    var fresh = outcome(() -> binding.environment().get());
                    builds++;
                    if (fresh instanceof String failure) {
                        assertEquals(failure, memo, step + "");
                        failures++;
                        continue;
                    }
                    for (String name : List.of("dimensionAttributes", "biomeAttributes", "timelines"))
                        assertEquals(field(fresh, name), field(memo, name), step + " " + name);
                    assertNotSame(fresh, memo);
                }
            }
        }
        assertTrue(hits > 50 && builds - failures > 300, hits + " hits of " + builds + ", failed " + failures);
    }

    @Test
    void simulatorTagsMatchTheMap() throws Exception {
        var state = new ClientComponentRegistries();
        var defaults = DataTables.defaults();
        var versions = List.of(ProtocolVersion.V26_3, ProtocolVersion.V1_21_11);
        var random = new Random(23);
        int hits = 0, queries = 0, failures = 0;
        for (int step = 0; step < 1000; step++) {
            switch (random.nextInt(8)) {
                case 0 -> state.appendTags(new RegistryTags(Map.of(
                        "minecraft:worldgen/biome",
                        new RegistryTags.Payload(Map.of("test:received", List.of(random.nextInt(4)))))));
                case 1 -> state.appendTags(new RegistryTags(Map.of(
                        random.nextBoolean() ? "minecraft:block" : "minecraft:worldgen/biome",
                        RegistryTags.Payload.EMPTY)));
                case 2 -> {
                    if (random.nextInt(3) == 0) {
                        state.beginConfiguration();
                        state.appendConfigurationRegistryData(new RegistryData(
                                "minecraft:worldgen/biome", List.of(new RegistryEntry("minecraft:plains", null))));
                        state.publish(
                                state.stageConfiguration(
                                        ProtocolVersion.V26_3, ProtocolVersion.V26_3, ProtocolVersion.V26_3, null),
                                ProtocolVersion.V26_3);
                    } else if (random.nextInt(4) == 0) state.fallback();
                }
                default -> {
                    var source = versions.get(random.nextInt(versions.size()));
                    var client = random.nextInt(4) == 0 ? versions.get(random.nextInt(versions.size())) : source;
                    var last = field(state, "lastSimulatorTags");
                    var memo = outcome(() -> state.blockSimulatorTags(source, client, defaults));
                    if (memo == last) hits++;
                    setField(state, "lastSimulatorTags", null);
                    var map = outcome(() -> state.blockSimulatorTags(source, client, defaults));
                    queries++;
                    if (map instanceof String failure) {
                        assertEquals(failure, memo, step + " " + source + " " + client);
                        failures++;
                    } else assertSame(map, memo, step + " " + source + " " + client);
                }
            }
        }
        assertTrue(hits > 50 && queries - failures > 150, hits + " hits of " + queries + ", failed " + failures);
    }

    private static Object outcome(Callable<Object> lookup) {
        try {
            return lookup.call();
        } catch (Exception failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    private static RegistryTags.Payload payload(Random random, List<String> tags, int size) {
        var payload = new HashMap<String, List<Integer>>();
        for (String tag : tags) {
            var members = new ArrayList<Integer>();
            for (int id = 0; id < size; id++) if (random.nextBoolean()) members.add(id);
            payload.put(tag, members);
        }
        return new RegistryTags.Payload(payload);
    }

    private static ClientWorldRegistries.Data defaults() {
        var definitions = new HashMap<String, List<ClientWorldRegistries.Definition>>();
        var tags = new HashMap<String, Map<String, List<String>>>();
        for (String registry : ClientWorldRegistries.REGISTRIES) {
            var values = ClientWorldDefaults.defaults();
            definitions.put(
                    registry,
                    values.entries(registry).entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> new ClientWorldRegistries.Definition(entry.getKey(), entry.getValue()))
                            .toList());
            tags.put(registry, values.tags(registry));
        }
        return new ClientWorldRegistries.Data(definitions, tags);
    }

    private static Map<String, List<RegistryEntry>> knownPacks(ClientWorldRegistries.Data data) {
        var entries = new HashMap<String, List<RegistryEntry>>();
        data.registries().forEach((registry, values) -> entries.put(
                registry, values.stream().map(value -> new RegistryEntry(value.key(), null)).toList()));
        return entries;
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
