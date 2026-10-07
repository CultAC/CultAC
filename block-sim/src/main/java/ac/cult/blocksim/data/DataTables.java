package ac.cult.blocksim.data;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Immutable checked-in facts. Loading never invokes a game or server class. */
public record DataTables(String version, String vanillaSha256, String reportSha256,
                         Set<String> enabledFeatures,
                         BlockRegistry registry, List<ItemDefinition> items,
                         Map<String, Set<String>> tags) {
    private static final class Defaults {
        static final DataTables VALUE;
        static {
            try { VALUE = load("26.3"); }
            catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
        }
    }
    public static DataTables defaults() { return Defaults.VALUE; }

    public DataTables {
        items = List.copyOf(items);
        enabledFeatures = Set.copyOf(enabledFeatures);
        var immutableTags = new HashMap<String, Set<String>>();
        tags.forEach((key, values) -> immutableTags.put(key, Set.copyOf(values)));
        tags = Map.copyOf(immutableTags);
    }

    public DataTables withEnabledFeatures(Set<String> received) {
        return enabledFeatures.equals(received) ? this
            : new DataTables(version, vanillaSha256, reportSha256, received, registry, items, tags);
    }

    public static DataTables load(String version) throws IOException {
        String path = "/block-sim/" + version + "/data.bin.gz";
        try (InputStream stream = DataTables.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing simulator data " + path);
            var tables = read(stream);
            if (!tables.version.equals(version)) throw new IOException("Simulator data version mismatch");
            return tables;
        }
    }

    public static DataTables read(InputStream stream) throws IOException {
        var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(stream)));
        if (in.readInt() != 0x4253494d || in.readInt() != 4) throw new IOException("Invalid block simulator data header");
        String version = in.readUTF(), vanillaHash = in.readUTF(), reportHash = in.readUTF();
        Set<String> features = Set.copyOf(strings(in));
        int maxValidChunkCoordinate = in.readInt();
        List<List<Box>> shapes = new ArrayList<>();
        for (int size = count(in), i = 0; i < size; i++) {
            List<Box> boxes = new ArrayList<>();
            for (int boxCount = count(in), j = 0; j < boxCount; j++) {
                boxes.add(new Box(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()));
            }
            shapes.add(List.copyOf(boxes));
        }
        List<BlockDefinition> blocks = new ArrayList<>();
        for (int size = count(in), i = 0; i < size; i++) {
            String key = in.readUTF(), vanillaClass = in.readUTF();
            int first = in.readInt(), states = in.readInt(), defaultState = in.readInt();
            List<BlockDefinition.Property> properties = new ArrayList<>();
            for (int propertyCount = count(in), j = 0; j < propertyCount; j++) {
                properties.add(new BlockDefinition.Property(in.readUTF(), in.readInt(), strings(in)));
            }
            blocks.add(new BlockDefinition(key, vanillaClass, first, states, defaultState, properties, stringMap(in), strings(in)));
        }
        List<StateFacts> facts = new ArrayList<>();
        for (int size = count(in), i = 0; i < size; i++) {
            float destroy = in.readFloat();
            int flags = in.readInt();
            String fluid = in.readUTF();
            int amount = in.readInt();
            boolean falling = in.readBoolean();
            int fluidBlock = in.readInt(), sturdy = in.readInt(), dynamic = in.readInt();
            facts.add(new StateFacts(destroy, flags, fluid, amount, falling, fluidBlock, sturdy, dynamic,
                shapes.get(in.readInt()), shapes.get(in.readInt()), shapes.get(in.readInt()), shapes.get(in.readInt()),
                in.readFloat(), in.readFloat(), in.readFloat(), StateFacts.PushReaction.valueOf(in.readUTF())));
        }
        List<ItemDefinition> items = new ArrayList<>();
        for (int size = count(in), i = 0; i < size; i++) {
            items.add(new ItemDefinition(in.readInt(), in.readUTF(), in.readUTF(), in.readUTF(), stringMap(in), readText(in)));
        }
        Map<String, Set<String>> tags = new HashMap<>();
        for (int size = count(in), i = 0; i < size; i++) tags.put(in.readUTF(), Set.copyOf(strings(in)));
        if (in.read() != -1) throw new IOException("Trailing simulator data");
        return new DataTables(version, vanillaHash, reportHash, features, new BlockRegistry(blocks, facts, maxValidChunkCoordinate), items, tags);
    }

    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 1_000_000) throw new IOException("Invalid table size " + count);
        return count;
    }
    private static List<String> strings(DataInputStream in) throws IOException {
        List<String> values = new ArrayList<>();
        for (int size = count(in), i = 0; i < size; i++) values.add(in.readUTF());
        return List.copyOf(values);
    }
    private static Map<String, String> stringMap(DataInputStream in) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (int size = count(in), i = 0; i < size; i++) values.put(in.readUTF(), in.readUTF());
        return Map.copyOf(values);
    }
    private static String readText(DataInputStream in) throws IOException {
        int length = count(in);
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated table string");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
