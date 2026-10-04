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
                String expected = "cult-model-registries\t1\t" + version.protocol() + "\t" + version.minecraftVersion();
                if (!expected.equals(reader.readLine()))
                    throw new ProtocolResolutionException("Wrong model registry version");
                var registries = new HashMap<String, List<String>>();
                var states = new ArrayList<String>();
                for (String line; (line = reader.readLine()) != null; ) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length == 4 && fields[0].equals("registry")) {
                        append(
                                registries.computeIfAbsent(fields[1], ignored -> new ArrayList<>()),
                                fields[2],
                                fields[3]);
                    } else if (fields.length == 3 && fields[0].equals("state")) {
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
        if (Integer.parseInt(id) != values.size() || name.isBlank())
            throw new ProtocolResolutionException("Non-contiguous model registry IDs");
        values.add(name);
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
