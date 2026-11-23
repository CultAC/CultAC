package ac.cult.blocksim.environment;

import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Named known-pack definitions and initial model order. Received wire IDs replace the model order. */
public final class ClientWorldDefaults {
    public record Entry(NbtValue.Compound data, String json) { }
    private static final class Defaults {
        static final ClientWorldDefaults VALUE = load();
    }
    private final Map<String, Map<String, Entry>> registries;
    private final Map<String, Map<String, List<String>>> tags;
    private final Map<String, List<String>> initialNames;
    private final Set<String> builtinRegistries, networkRegistries;
    private final Set<String> syncableAttributes;
    private final Set<String> booleanAttributes;
    private ClientWorldDefaults(Map<String, Map<String, Entry>> registries, Map<String, Map<String, List<String>>> tags, Set<String> syncableAttributes, Set<String> booleanAttributes, Map<String, List<String>> initialNames, Set<String> builtinRegistries, Set<String> networkRegistries) {
        this.registries = Map.copyOf(registries); this.tags = Map.copyOf(tags); this.initialNames = Map.copyOf(initialNames);
        this.syncableAttributes = Set.copyOf(syncableAttributes);
        this.booleanAttributes = Set.copyOf(booleanAttributes);
        this.builtinRegistries = Set.copyOf(builtinRegistries);
        this.networkRegistries = Set.copyOf(networkRegistries);
    }
    public Map<String, List<String>> initialNames() { return initialNames; }
    public Set<String> builtinRegistries() { return builtinRegistries; }
    public Set<String> networkRegistries() { return networkRegistries; }
    public List<String> initialNames(String registry) {
        return Objects.requireNonNull(initialNames.get(HolderSets.identifier(registry)), "Missing initial model registry " + registry);
    }
    public Set<String> syncableAttributes() { return syncableAttributes; }
    public Set<String> booleanAttributes() { return booleanAttributes; }
    public static ClientWorldDefaults defaults() { return Defaults.VALUE; }
    public Map<String, Entry> entries(String registry) {
        var entries = registries.get(HolderSets.identifier(registry));
        if (entries == null) throw new IllegalArgumentException("Unknown bundled world registry " + registry);
        return entries;
    }
    public Entry resolve(String registry, String key) {
        var entry = entries(registry).get(HolderSets.identifier(key));
        if (entry == null) throw new IllegalArgumentException("Unknown bundled " + registry + " entry " + key);
        return entry;
    }
    public Map<String, List<String>> tags(String registry) {
        var tags = this.tags.get(HolderSets.identifier(registry));
        if (tags == null) throw new IllegalArgumentException("Unknown bundled world registry " + registry);
        return tags;
    }
    private static ClientWorldDefaults load() {
        String path = "/block-sim/26.3/client-world-defaults.bin.gz";
        var stream = ClientWorldDefaults.class.getResourceAsStream(path);
        if (stream == null) throw new ExceptionInInitializerError("Missing bundled world registries " + path);
        try (var input = new DataInputStream(new GZIPInputStream(stream))) {
            if (input.readInt() != 0x43574446 || input.readInt() != 5 || !input.readUTF().equals("26.3"))
                throw new IOException("Unsupported world registry table");
            if (!input.readUTF().equals("4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d"))
                throw new IOException("Unpinned world registry table");
            var registries = new HashMap<String, Map<String, Entry>>();
            var registryTags = new HashMap<String, Map<String, List<String>>>();
            int registryCount = input.readInt();
            for (int registry = 0; registry < registryCount; registry++) {
                String name = input.readUTF();
                var entries = new HashMap<String, Entry>();
                int entryCount = input.readInt();
                for (int index = 0; index < entryCount; index++) {
                    String key = input.readUTF();
                    byte[] nbt = new byte[input.readInt()]; input.readFully(nbt);
                    byte[] json = new byte[input.readInt()]; input.readFully(json);
                    entries.put(key, new Entry((NbtValue.Compound) CanonicalSnbt.parse(new String(nbt, StandardCharsets.UTF_8)),
                            new String(json, StandardCharsets.UTF_8)));
                }
                registries.put(name, Map.copyOf(entries));
                var tags = new HashMap<String, List<String>>();
                int tagCount = input.readInt();
                for (int tag = 0; tag < tagCount; tag++) {
                    String key = input.readUTF();
                    var members = new ArrayList<String>();
                    int memberCount = input.readInt();
                    for (int member = 0; member < memberCount; member++) members.add(input.readUTF());
                    tags.put(key, List.copyOf(members));
                }
                registryTags.put(name, Map.copyOf(tags));
            }
            var syncable = new HashSet<String>();
            var booleans = new HashSet<String>();
            int attributeCount = input.readInt();
            for (int index = 0; index < attributeCount; index++) {
                String key = input.readUTF();
                if (input.readBoolean()) syncable.add(key);
                if (input.readBoolean()) booleans.add(key);
            }
            var initialNames = new HashMap<String, List<String>>();
            int initialRegistryCount = input.readInt();
            for (int registry = 0; registry < initialRegistryCount; registry++) {
                String name = input.readUTF();
                var names = new ArrayList<String>();
                int count = input.readInt();
                for (int id = 0; id < count; id++) names.add(input.readUTF());
                initialNames.put(name, List.copyOf(names));
            }
            var builtinRegistries = new HashSet<String>();
            var networkRegistries = new HashSet<String>();
            int builtinCount = input.readInt();
            for (int index = 0; index < builtinCount; index++) builtinRegistries.add(input.readUTF());
            int networkCount = input.readInt();
            for (int index = 0; index < networkCount; index++) networkRegistries.add(input.readUTF());
            if (input.read() != -1) throw new IOException("Trailing world registry data");
            return new ClientWorldDefaults(registries, registryTags, syncable, booleans, initialNames, builtinRegistries, networkRegistries);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
