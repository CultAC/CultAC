package ac.cult.cultac.protocol.data;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Exact static vanilla IDs. Dynamic/custom registries still come from connection configuration. */
public final class ModelRegistryData {
    private static final Map<ProtocolVersion, ModelRegistryData> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private final ProtocolVersion version;
    private final Map<String, IdTable> registries;
    private final List<String> states;
    private final Map<String, Integer> stateIds;

    private ModelRegistryData(ProtocolVersion version, Map<String, List<String>> registries, List<String> states) {
        this.version = version;
        var tables = new HashMap<String, IdTable>();
        registries.forEach((key, names) -> tables.put(key, new IdTable(version + "/" + key, names)));
        this.registries = Map.copyOf(tables);
        this.states = List.copyOf(states);
        var ids = new HashMap<String, Integer>();
        for (int id = 0; id < states.size(); id++) {
            if (ids.put(states.get(id), id) != null) throw new ProtocolResolutionException("Duplicate block state");
        }
        this.stateIds = Map.copyOf(ids);
    }

    public static ModelRegistryData load(ProtocolVersion version) {
        return CACHE.computeIfAbsent(version, ModelRegistryData::read);
    }

    private static ModelRegistryData read(ProtocolVersion version) {
        String path = "/ac/cult/cultac/protocol/model/" + version.protocol() + ".tsv.gz";
        try (var resource = ModelRegistryData.class.getResourceAsStream(path)) {
            if (resource == null) throw new ProtocolResolutionException("Missing model registry data for " + version);
            try (var reader =
                    new BufferedReader(new InputStreamReader(new GZIPInputStream(resource), StandardCharsets.UTF_8))) {
                // Format 2 (scripts/compact-model-registries.py) leaves IDs implicit and lists each block's
                // property values once instead of every state.
                String header = reader.readLine();
                String versionSuffix = "\t" + version.protocol() + "\t" + version.minecraftVersion();
                boolean compact = ("cult-model-registries\t2" + versionSuffix).equals(header);
                if (!compact && !("cult-model-registries\t1" + versionSuffix).equals(header))
                    throw new ProtocolResolutionException("Wrong model registry version");
                var registries = new HashMap<String, List<String>>();
                var states = new ArrayList<String>();
                for (String line; (line = reader.readLine()) != null; ) {
                    String[] fields = line.split("\t", -1);
                    if (compact && fields.length == 3 && fields[0].equals("registry")) {
                        add(registries.computeIfAbsent(fields[1], ignored -> new ArrayList<>()), fields[2]);
                    } else if (compact && fields.length >= 2 && fields[0].equals("block")) {
                        appendBlock(states, fields);
                    } else if (compact && fields.length == 2 && fields[0].equals("state")) {
                        add(states, fields[1]);
                    } else if (!compact && fields.length == 4 && fields[0].equals("registry")) {
                        append(
                                registries.computeIfAbsent(fields[1], ignored -> new ArrayList<>()),
                                fields[2],
                                fields[3]);
                    } else if (!compact && fields.length == 3 && fields[0].equals("state")) {
                        append(states, fields[1], fields[2]);
                    } else throw new ProtocolResolutionException("Invalid model registry row");
                }
                if (states.isEmpty() || !registries.containsKey("minecraft:item"))
                    throw new ProtocolResolutionException("Incomplete model registry data");
                return new ModelRegistryData(version, registries, states);
            }
        } catch (IOException failure) {
            throw new ProtocolResolutionException("Cannot read model registry data: " + failure.getMessage());
        }
    }

    private static void append(List<String> values, String id, String name) {
        if (Integer.parseInt(id) != values.size())
            throw new ProtocolResolutionException("Non-contiguous model registry IDs");
        add(values, name);
    }

    private static void add(List<String> values, String name) {
        if (name.isBlank()) throw new ProtocolResolutionException("Blank model registry name");
        values.add(name);
    }

    /** {@code block, name, key=v1,v2, ...}: every combination in order, the last property varying fastest. */
    private static void appendBlock(List<String> states, String[] fields) {
        int properties = fields.length - 2;
        var keys = new String[properties];
        var values = new String[properties][];
        for (int i = 0; i < properties; i++) {
            String[] property = fields[i + 2].split("=", 2);
            if (property.length != 2 || property[0].isEmpty() || property[1].isEmpty())
                throw new ProtocolResolutionException("Invalid model registry block property");
            keys[i] = property[0];
            values[i] = property[1].split(",", -1);
            for (String value : values[i])
                if (value.isEmpty()) throw new ProtocolResolutionException("Invalid model registry block property");
        }
        if (properties == 0) {
            add(states, fields[1]);
            return;
        }
        var index = new int[properties];
        while (true) {
            var state = new StringBuilder(fields[1]).append('[');
            for (int i = 0; i < properties; i++) {
                if (i > 0) state.append(',');
                state.append(keys[i]).append('=').append(values[i][index[i]]);
            }
            add(states, state.append(']').toString());
            int i = properties - 1;
            while (i >= 0 && ++index[i] == values[i].length) index[i--] = 0;
            if (i < 0) return;
        }
    }

    public ProtocolVersion version() {
        return version;
    }

    public List<String> blockStates() {
        return states;
    }

    public int blockStateId(String state) {
        return stateIds.getOrDefault(state, -1);
    }

    public boolean hasRegistry(String key) {
        return registries.containsKey(key);
    }

    Set<String> registryKeys() {
        return registries.keySet();
    }

    public IdTable registry(String key) {
        var table = registries.get(key);
        if (table == null) throw new ProtocolResolutionException("Unknown static registry " + key);
        return table;
    }

    public String blockStateName(int id) {
        if (id < 0 || id >= states.size()) throw new MalformedPacketException("Unknown block state ID " + id);
        return states.get(id);
    }

    /** -1 means absent in the target; callers must not silently substitute air or an unrelated ID. */
    public int translateBlockState(int id, ModelRegistryData target) {
        return target.blockStateId(blockStateName(id));
    }

    /** Translates IDs only; component payloads require the exact source protocol's codecs. */
    public int translateRegistryId(String registry, int id, ModelRegistryData target) {
        return target.registry(registry).id(registry(registry).name(id));
    }
}
