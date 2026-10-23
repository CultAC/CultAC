package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/** Source-version NbtUtils/BlockState.CODEC, before client-directed state projection. */
final class ClientBlockStateNbt {
    private record Property(Set<String> values, boolean integer) {
        String parse(String value) {
            if (integer) {
                try {
                    value = Integer.toString(Integer.parseInt(value));
                } catch (NumberFormatException invalid) {
                    return null;
                }
            }
            return values.contains(value) ? value : null;
        }
    }

    private record Block(int defaultState, Map<String, String> defaults, Map<String, Property> properties) {}

    private final ModelRegistryData registry;
    private final Map<String, Block> blocks;
    private final int air;
    private final boolean modern;

    ClientBlockStateNbt(ProtocolVersion version) {
        registry = ModelRegistryData.load(version);
        modern = version.atLeast(ProtocolVersion.V26_3);
        var definitions = new HashMap<String, Map<String, Set<String>>>();
        for (String state : registry.blockStates()) {
            var properties = definitions.computeIfAbsent(blockName(state), ignored -> new TreeMap<>());
            stateProperties(state)
                    .forEach((name, value) -> properties
                            .computeIfAbsent(name, ignored -> new HashSet<>())
                            .add(value));
        }
        var defaults = new HashMap<String, Block>();
        String resource = "/ac/cult/cultac/block-state-defaults/" + version.protocol() + ".tsv.gz";
        try (var stream = ClientBlockStateNbt.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing native block defaults: " + version);
            try (var reader =
                    new BufferedReader(new InputStreamReader(new GZIPInputStream(stream), StandardCharsets.UTF_8))) {
                if (!("cult-client-block-defaults\t2\t" + version.protocol()).equals(reader.readLine()))
                    throw new IllegalStateException("Wrong native block defaults: " + version);
                for (String row; (row = reader.readLine()) != null; ) {
                    var fields = row.split("\t", -1);
                    int id = Integer.parseInt(fields[1]);
                    String state = registry.blockStateName(id);
                    if (!blockName(state).equals(fields[0]))
                        throw new IllegalStateException("Native default belongs to another block");
                    var properties = new TreeMap<String, Property>();
                    var integers = Set.of(fields[2].split(","));
                    definitions
                            .get(fields[0])
                            .forEach((name, values) ->
                                    properties.put(name, new Property(Set.copyOf(values), integers.contains(name))));
                    defaults.put(fields[0], new Block(id, stateProperties(state), properties));
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read native block defaults", error);
        }
        if (!defaults.keySet().equals(definitions.keySet()))
            throw new IllegalStateException("Incomplete native block defaults: " + version);
        blocks = Map.copyOf(defaults);
        air = blocks.get("minecraft:air").defaultState();
    }

    int read(NbtValue encoded) {
        String name = null;
        NbtValue properties = null;
        if (modern && encoded instanceof NbtValue.Text text) name = text.value();
        else if (encoded instanceof NbtValue.Compound tag) {
            if (tag.values().get(modern ? "id" : "Name") instanceof NbtValue.Text text) name = text.value();
            properties = tag.values().get(modern ? "properties" : "Properties");
        }
        if (name == null) return air;
        if (!name.contains(":")) name = "minecraft:" + name;
        var block = blocks.get(name);
        if (block == null) return air;
        if (!(properties instanceof NbtValue.Compound tag) || block.properties().isEmpty()) return block.defaultState();
        var values = new TreeMap<>(block.defaults());
        // Both the old helper and property codecs retain the native default
        // for absent/invalid properties and ignore unrecognized property names.
        for (var entry : tag.values().entrySet()) {
            var property = block.properties().get(entry.getKey());
            if (property != null && entry.getValue() instanceof NbtValue.Text text) {
                String value = property.parse(text.value());
                if (value != null) values.put(entry.getKey(), value);
            }
        }
        var serialized = new StringJoiner(",", name + "[", "]");
        values.forEach((key, value) -> serialized.add(key + "=" + value));
        int state = registry.blockStateId(serialized.toString());
        if (state < 0)
            throw new IllegalStateException("Decoded state is absent from the source registry: " + serialized);
        return state;
    }

    private static String blockName(String state) {
        int start = state.indexOf('[');
        return start < 0 ? state : state.substring(0, start);
    }

    private static Map<String, String> stateProperties(String state) {
        var result = new TreeMap<String, String>();
        int start = state.indexOf('[');
        if (start >= 0)
            for (String field : state.substring(start + 1, state.length() - 1).split(",")) {
                int equals = field.indexOf('=');
                result.put(field.substring(0, equals), field.substring(equals + 1));
            }
        return result;
    }
}
