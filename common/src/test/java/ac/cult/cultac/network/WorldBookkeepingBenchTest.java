package ac.cult.cultac.network;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedBlockEntities;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import com.sun.management.ThreadMXBean;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation of world bookkeeping, the map path beside the world's own, reps interleaved. CULT_BENCH=1
 * enables it; CULT_BENCH_OUT and CULT_BENCH_LABEL name the output.
 */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class WorldBookkeepingBenchTest {
    private static final int REPS = 9;
    private static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    static long sink;

    /**
     * Per simulated tick: a 3x3x3 block scan around a walking player (one lookup per block, as getBlockStateIdAt
     * does), 20 entity column checks within 40 blocks, and the heightmap column under the player.
     */
    @Test
    void columnLookups() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var world = fixture.player.compensatedWorld;
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) world.ensureValidationChunkLoaded(x, z);
            Long2ObjectMap<CachedChunk> chunks = WorldBookkeepingTest.chunks(world);
            var random = new Random(11);
            int ticks = 2000, perTick = 27 + 20 + 1;
            int[] xs = new int[ticks * perTick], zs = new int[ticks * perTick];
            boolean[] loadedOnly = new boolean[ticks * perTick];
            double px = 0, pz = 0;
            int n = 0;
            for (int tick = 0; tick < ticks; tick++) {
                px += random.nextGaussian() * 0.3;
                pz += random.nextGaussian() * 0.3;
                int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = 0; dy < 3; dy++)
                        for (int dz = -1; dz <= 1; dz++) {
                            xs[n] = (bx + dx) >> 4;
                            zs[n++] = (bz + dz) >> 4;
                        }
                for (int entity = 0; entity < 20; entity++) {
                    xs[n] = (bx + random.nextInt(81) - 40) >> 4;
                    zs[n] = (bz + random.nextInt(81) - 40) >> 4;
                    loadedOnly[n++] = true;
                }
                xs[n] = bx >> 4;
                zs[n++] = bz >> 4;
            }
            int queries = n;
            int repeats = 0;
            for (int i = 1; i < queries; i++) if (xs[i] == xs[i - 1] && zs[i] == zs[i - 1]) repeats++;
            line("column-lookups consecutive-repeat " + (repeats * 100 / queries) + "%");
            var bodies = new LinkedHashMap<String, LongUnaryOperator>();
            bodies.put("column-lookups map", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int i = 0; i < queries; i++) {
                        long position = CompensatedWorld.chunkPositionToLong(xs[i], zs[i]);
                        if (loadedOnly[i]) seen += chunks.containsKey(position) ? 1 : 0;
                        else seen += chunks.get(position) == null ? 0 : 1;
                    }
                return seen;
            });
            bodies.put("column-lookups world", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int i = 0; i < queries; i++) {
                        if (loadedOnly[i]) seen += world.isChunkLoaded(xs[i], zs[i]) ? 1 : 0;
                        else seen += world.getChunk(xs[i], zs[i]) == null ? 0 : 1;
                    }
                return seen;
            });
            interleaved(queries, bodies);
        }
    }

    /**
     * A 13-column unload edge as the player crosses a chunk border, with 300 block entities stored in the 7x7 columns
     * around the player: every unloaded column holds none.
     */
    @Test
    void blockEntityForgets() throws Exception {
        int chest = DataTables.defaults().registry().block("minecraft:chest").defaultState();
        var entities = new CompensatedBlockEntities(InteractionRegistries::defaults);
        var random = new Random(5);
        for (int i = 0; i < 300; i++)
            entities.snapshot(
                    new BlockPos(random.nextInt(112) - 56, random.nextInt(128) - 64, random.nextInt(112) - 56), chest);
        var entries = (Long2ObjectOpenHashMap<?>) WorldBookkeepingTest.field(entities, "entries");
        int edge = 13;
        var bodies = new LinkedHashMap<String, LongUnaryOperator>();
        bodies.put("block-entity-forgets scan", units -> {
            long seen = 0;
            for (long unit = 0; unit < units; unit++)
                for (int z = -6; z <= 6; z++) {
                    var iterator = entries.long2ObjectEntrySet().fastIterator();
                    while (iterator.hasNext()) {
                        long key = iterator.next().getLongKey();
                        if (((int) (key >> 38) >> 4) == 7 && ((int) (key << 26 >> 38) >> 4) == z) seen++;
                    }
                }
            return seen;
        });
        bodies.put("block-entity-forgets counted", units -> {
            for (long unit = 0; unit < units; unit++) for (int z = -6; z <= 6; z++) entities.forgetChunk(7, z);
            return entries.size();
        });
        interleaved(edge, bodies);
    }

    /** Each body sized to at least 1 s of thread CPU per rep, their reps alternating so host load falls on all alike. */
    static void interleaved(int perRun, Map<String, LongUnaryOperator> bodies) throws Exception {
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
        for (int body = 0; body < names.size(); body++)
            line(names.get(body) + " ps/item " + summary(nanos[body]) + " | milli-bytes/item " + summary(bytes[body]));
    }

    static void line(String text) throws Exception {
        String line = System.getenv().getOrDefault("CULT_BENCH_LABEL", "run") + " " + text + System.lineSeparator();
        System.out.print(line);
        String out = System.getenv("CULT_BENCH_OUT");
        if (out != null)
            Files.writeString(
                    Path.of(out), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
}
