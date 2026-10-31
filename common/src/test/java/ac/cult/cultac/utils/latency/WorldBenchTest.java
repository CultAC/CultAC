package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.cultac.network.packet.SectionSnapshot;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.function.LongUnaryOperator;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation of chunk-section decoding, counting and block-entity ticks on fixture terrain. CULT_BENCH=1
 * enables it; CULT_BENCH_FIXTURES lists the diagfix directories, CULT_BENCH_OUT and CULT_BENCH_LABEL name the output.
 */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class WorldBenchTest {
    private static final int REPS = 9;
    private static final int SECTIONS_PER_CHUNK = 24;
    private static final int BIOMES = 64;
    private static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final int STATES = REGISTRY.stateCount();
    private static final Map<String, Integer> STATE_CACHE = new HashMap<>();
    static long sink;

    @Test
    void chunkSections() throws Exception {
        List<byte[]> chunks = columns(loadSections());
        int sectionsTotal = chunks.size() * SECTIONS_PER_CHUNK;
        List<SectionSnapshot[]> decoded = new ArrayList<>();
        for (byte[] chunk : chunks) decoded.add(decode(chunk));
        report("decode", sectionsTotal, units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++) for (byte[] chunk : chunks) seen += decode(chunk).length;
            return seen;
        });
        report("handler", sectionsTotal, units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++)
                for (SectionSnapshot[] snapshots : decoded) {
                    CachedSection[] cached = new CachedSection[snapshots.length];
                    int[][] biomes = new int[snapshots.length][];
                    for (int i = 0; i < snapshots.length; i++) {
                        cached[i] = new CachedSection(snapshots[i].copyBlocks());
                        biomes[i] = snapshots[i].copyBiomes();
                    }
                    var column = new CachedChunk(cached, 0, List.of(), biomes);
                    for (CachedSection section : cached) if (section.isEmpty() && !section.hasFluid()) seen++;
                    seen += column.biomeAt(0, 0, 0, 0);
                }
            return seen;
        });
        report("raw", sectionsTotal, units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++)
                for (byte[] chunk : chunks) {
                    var bytes = Unpooled.wrappedBuffer(chunk);
                    CachedSection[] cached = new CachedSection[SECTIONS_PER_CHUNK];
                    int[][] biomes = new int[SECTIONS_PER_CHUNK][];
                    for (int i = 0; i < SECTIONS_PER_CHUNK; i++) {
                        bytes.readShort();
                        bytes.readShort();
                        cached[i] = new CachedSection(PalettedSection.readBlocks(bytes));
                        biomes[i] = PalettedSection.readBiomes(bytes, BIOMES);
                    }
                    bytes.release();
                    var column = new CachedChunk(cached, 0, List.of(), biomes);
                    for (CachedSection section : cached) if (section.isEmpty() && !section.hasFluid()) seen++;
                    seen += column.biomeAt(0, 0, 0, 0);
                }
            return seen;
        });
    }

    @Test
    void blockEntityTick() throws Exception {
        var entities = new CompensatedBlockEntities(InteractionRegistries::defaults);
        int chest = REGISTRY.block("minecraft:chest").defaultState();
        int shulker = REGISTRY.block("minecraft:shulker_box").defaultState();
        for (int i = 0; i < 1500; i++) entities.snapshot(new BlockPos(i % 40, 60 + i / 1600, i / 40), chest);
        for (int i = 0; i < 14; i++) {
            var pos = new BlockPos(i, 100, 0);
            entities.blockEvent(pos, shulker, 1, 1);
            entities.blockEvent(pos, shulker, 1, 0);
        }
        for (int tick = 0; tick < 40; tick++) entities.tick();
        report("block-entity-tick", 1, ticks -> {
            for (long tick = 0; tick < ticks; tick++) entities.tick();
            return ticks;
        });
    }

    /** A second player loading the columns a first player holds, its block storage shared through the pools. */
    @Test
    void twoPlayersOneWorld() throws Exception {
        List<byte[]> chunks = columns(loadSections());
        String dimension = "bench:two-players";
        int sectionsTotal = chunks.size() * SECTIONS_PER_CHUNK;
        for (int column = 0; column < chunks.size(); column++) {
            SectionSnapshot[] snapshots = decode(chunks.get(column));
            for (int i = 0; i < snapshots.length; i++)
                SectionPool.forChunk(dimension, column, i, 0)
                        .internRetained(new CachedSection(snapshots[i].copyBlocks()));
        }
        long shared = 0, offered = 0;
        long offers = SectionPool.wireOfferCounter.sum(), poolHits = SectionPool.wireHitCounter.sum();
        for (int column = 0; column < chunks.size(); column++) {
            var bytes = Unpooled.wrappedBuffer(chunks.get(column));
            for (int i = 0; i < SECTIONS_PER_CHUNK; i++) {
                bytes.readShort();
                bytes.readShort();
                var pool = SectionPool.existing(dimension, column, i, 0);
                var view = pool == null
                        ? null
                        : pool.sharedBlocks(bytes, false, STATES, IntUnaryOperator.identity(), STATES);
                if (pool != null) offered++;
                if (view != null) shared++;
                else PalettedSection.readBlocks(bytes);
                PalettedSection.readBiomes(bytes, BIOMES);
            }
            bytes.release();
        }
        assertEquals(offered, SectionPool.wireOfferCounter.sum() - offers);
        assertEquals(shared, SectionPool.wireHitCounter.sum() - poolHits);
        String hits = "shared " + shared + " of " + sectionsTotal + " sections";
        System.out.println(System.getenv().getOrDefault("CULT_BENCH_LABEL", "run") + " two-players " + hits);
        var bodies = new LinkedHashMap<String, LongUnaryOperator>();
        bodies.put("decode-first", units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++) for (byte[] chunk : chunks) seen += decode(chunk).length;
            return seen;
        });
        bodies.put("decode-second", units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++)
                for (int column = 0; column < chunks.size(); column++) {
                    var bytes = Unpooled.wrappedBuffer(chunks.get(column));
                    for (int i = 0; i < SECTIONS_PER_CHUNK; i++) {
                        var snapshot = SectionSnapshot.read(
                                bytes,
                                ProtocolVersion.V26_3,
                                STATES,
                                STATES,
                                BIOMES,
                                BIOMES,
                                IntUnaryOperator.identity(),
                                IntUnaryOperator.identity(),
                                SectionPool.existing(dimension, column, i, 0));
                        seen += snapshot.uniformBiome();
                    }
                    bytes.release();
                }
            return seen;
        });
        bodies.put("raw-first", units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++)
                for (byte[] wire : chunks) {
                    var bytes = Unpooled.wrappedBuffer(wire);
                    CachedSection[] cached = new CachedSection[SECTIONS_PER_CHUNK];
                    int[][] biomes = new int[SECTIONS_PER_CHUNK][];
                    for (int i = 0; i < SECTIONS_PER_CHUNK; i++) {
                        bytes.readShort();
                        bytes.readShort();
                        cached[i] = new CachedSection(PalettedSection.readBlocks(bytes));
                        biomes[i] = PalettedSection.readBiomeValues(bytes, BIOMES);
                    }
                    bytes.release();
                    var chunk = CachedChunk.received(cached, 0, List.of(), biomes);
                    for (CachedSection section : cached) if (section.isEmpty() && !section.hasFluid()) seen++;
                    seen += chunk.biomeAt(0, 0, 0, 0);
                }
            return seen;
        });
        bodies.put("raw-second", units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++)
                for (int column = 0; column < chunks.size(); column++) {
                    var bytes = Unpooled.wrappedBuffer(chunks.get(column));
                    CachedSection[] cached = new CachedSection[SECTIONS_PER_CHUNK];
                    int[][] biomes = new int[SECTIONS_PER_CHUNK][];
                    for (int i = 0; i < SECTIONS_PER_CHUNK; i++) {
                        bytes.readShort();
                        bytes.readShort();
                        var pool = SectionPool.existing(dimension, column, i, 0);
                        cached[i] = new CachedSection(PalettedSection.readBlocks(bytes, pool));
                        biomes[i] = PalettedSection.readBiomeValues(bytes, BIOMES);
                    }
                    bytes.release();
                    var chunk = CachedChunk.received(cached, 0, List.of(), biomes);
                    for (CachedSection section : cached) if (section.isEmpty() && !section.hasFluid()) seen++;
                    seen += chunk.biomeAt(0, 0, 0, 0);
                }
            return seen;
        });
        interleaved(sectionsTotal, bodies);
    }

    /** The sections as chunk section data, {@value #SECTIONS_PER_CHUNK} to a column. */
    private static List<byte[]> columns(List<int[]> sections) {
        List<byte[]> chunks = new ArrayList<>();
        for (int start = 0; start < sections.size(); start += SECTIONS_PER_CHUNK) {
            ByteBuf wire = Unpooled.buffer();
            for (int i = 0; i < SECTIONS_PER_CHUNK; i++) {
                int index = start + i;
                int[] states = index < sections.size() ? sections.get(index) : new int[4096];
                wire.writeShort(0);
                wire.writeShort(0);
                encode(wire, states, STATES);
                int[] biomes = new int[BIOMES];
                Arrays.fill(biomes, 1);
                if (index % 8 == 7) for (int cell = 32; cell < BIOMES; cell++) biomes[cell] = 2;
                encode(wire, biomes, BIOMES);
            }
            chunks.add(ByteBufUtil.getBytes(wire));
            wire.release();
        }
        return chunks;
    }

    private static SectionSnapshot[] decode(byte[] chunk) {
        var bytes = Unpooled.wrappedBuffer(chunk);
        var result = new SectionSnapshot[SECTIONS_PER_CHUNK];
        for (int i = 0; i < result.length; i++)
            result[i] = SectionSnapshot.read(
                    bytes,
                    ProtocolVersion.V26_3,
                    STATES,
                    STATES,
                    BIOMES,
                    BIOMES,
                    IntUnaryOperator.identity(),
                    IntUnaryOperator.identity());
        bytes.release();
        return result;
    }

    private static void report(String name, int perRun, LongUnaryOperator body) throws Exception {
        interleaved(perRun, Map.of(name, body));
    }

    /** Each body sized to at least 1 s of thread CPU per rep, their reps alternating so host load falls on all alike. */
    private static void interleaved(int perRun, Map<String, LongUnaryOperator> bodies) throws Exception {
        var names = List.copyOf(bodies.keySet());
        long[] units = new long[names.size()];
        long[][] nanos = new long[names.size()][REPS], bytes = new long[names.size()][REPS];
        for (int body = 0; body < names.size(); body++) {
            units[body] = 1;
            while (cpu(bodies.get(names.get(body)), units[body]) < 1_000_000_000L) units[body] *= 2;
        }
        for (int warm = 0; warm < 3; warm++)
            for (int body = 0; body < names.size(); body++)
                sink += bodies.get(names.get(body)).applyAsLong(units[body]);
        for (int rep = 0; rep < REPS; rep++)
            for (int body = 0; body < names.size(); body++) {
                long allocated = BEAN.getCurrentThreadAllocatedBytes(), start = BEAN.getCurrentThreadCpuTime();
                sink += bodies.get(names.get(body)).applyAsLong(units[body]);
                long work = units[body] * perRun;
                nanos[body][rep] = (BEAN.getCurrentThreadCpuTime() - start) * 1000 / work;
                bytes[body][rep] = (BEAN.getCurrentThreadAllocatedBytes() - allocated) * 1000 / work;
            }
        String label = System.getenv().getOrDefault("CULT_BENCH_LABEL", "run");
        String out = System.getenv("CULT_BENCH_OUT");
        for (int body = 0; body < names.size(); body++) {
            String line = label + " " + names.get(body) + " ps/item " + summary(nanos[body]) + " | milli-bytes/item "
                    + summary(bytes[body]) + System.lineSeparator();
            System.out.print(line);
            if (out != null)
                Files.writeString(
                        Path.of(out),
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
        }
    }

    private static long cpu(LongUnaryOperator body, long units) {
        long start = BEAN.getCurrentThreadCpuTime();
        sink += body.applyAsLong(units);
        return BEAN.getCurrentThreadCpuTime() - start;
    }

    private static String summary(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return "median=" + sorted[sorted.length / 2] + " min=" + sorted[0] + " max=" + sorted[sorted.length - 1];
    }

    /** A paletted container as a vanilla server writes it: first-seen palette at the minimal local width. */
    private static void encode(ByteBuf out, int[] values, int registry) {
        var ids = new LinkedHashMap<Integer, Integer>();
        for (int value : values) ids.putIfAbsent(value, ids.size());
        if (ids.size() == 1) {
            out.writeByte(0);
            Wire.writeVarInt(out, values[0]);
            return;
        }
        boolean blocks = values.length == 4096;
        int needed = 32 - Integer.numberOfLeadingZeros(ids.size() - 1);
        boolean global = needed > (blocks ? 8 : 3);
        int bits = global ? 32 - Integer.numberOfLeadingZeros(registry - 1) : Math.max(blocks ? 4 : 1, needed);
        out.writeByte(bits);
        if (!global) {
            Wire.writeVarInt(out, ids.size());
            for (int value : ids.keySet()) Wire.writeVarInt(out, value);
        }
        int perWord = 64 / bits;
        long[] words = new long[(values.length + perWord - 1) / perWord];
        for (int i = 0; i < values.length; i++) {
            long code = global ? values[i] : ids.get(values[i]);
            words[i / perWord] |= code << ((i % perWord) * bits);
        }
        for (long word : words) out.writeLong(word);
    }

    private static List<int[]> loadSections() throws Exception {
        List<int[]> result = new ArrayList<>();
        String dirs = System.getenv("CULT_BENCH_FIXTURES");
        if (dirs == null) throw new IllegalStateException("CULT_BENCH_FIXTURES names no fixture directory");
        for (String dir : dirs.split(File.pathSeparator)) {
            List<Path> files;
            try (var listing = Files.list(Path.of(dir))) {
                files = listing.filter(path -> path.toString().endsWith(".diagfix.json.gz"))
                        .sorted()
                        .toList();
            }
            for (Path file : files) {
                JsonObject json;
                try (var in = new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) {
                    json = JsonParser.parseReader(in).getAsJsonObject();
                }
                for (JsonElement world : json.getAsJsonArray("worlds"))
                    for (JsonElement column : world.getAsJsonObject().getAsJsonArray("columns"))
                        for (JsonElement element : column.getAsJsonObject().getAsJsonArray("sections")) {
                            JsonObject section = element.getAsJsonObject();
                            if (section.has("kind")) continue;
                            var palette = section.getAsJsonArray("palette");
                            int[] states = new int[palette.size()];
                            for (int i = 0; i < states.length; i++)
                                states[i] = state(palette.get(i).getAsString());
                            int[] values = new int[4096];
                            if (!section.has("data")) {
                                Arrays.fill(values, states[0]);
                            } else {
                                byte[] data = Base64.getDecoder()
                                        .decode(section.get("data").getAsString());
                                boolean wide = data.length == 8192;
                                for (int i = 0; i < 4096; i++)
                                    values[i] = states[
                                            wide ? (data[2 * i] & 0xFF) << 8 | data[2 * i + 1] & 0xFF : data[i] & 0xFF];
                            }
                            result.add(values);
                        }
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("No palette sections under " + dirs);
        return result;
    }

    private static int state(String text) {
        return STATE_CACHE.computeIfAbsent(text, key -> {
            int bracket = key.indexOf('[');
            String name = bracket < 0 ? key : key.substring(0, bracket);
            if (name.indexOf(':') < 0) name = "minecraft:" + name;
            int state;
            try {
                state = REGISTRY.block(name).defaultState();
            } catch (IllegalArgumentException unknown) {
                return REGISTRY.block("minecraft:stone").defaultState();
            }
            if (bracket >= 0)
                for (String pair : key.substring(bracket + 1, key.length() - 1).split(",")) {
                    int equals = pair.indexOf('=');
                    state = REGISTRY.withIfValid(state, pair.substring(0, equals), pair.substring(equals + 1));
                }
            return state;
        });
    }
}
