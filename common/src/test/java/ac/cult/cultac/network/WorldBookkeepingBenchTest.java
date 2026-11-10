package ac.cult.cultac.network;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.ShulkerData;
import ac.cult.cultac.utils.latency.CompensatedBlockEntities;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import com.sun.management.ThreadMXBean;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
     * does), 20 entity column checks within 40 blocks, and the heightmap column under the player. The warm runs use
     * one world; the cold run walks 24 worlds of 21x21 columns a tick at a time, as an event loop serves its players.
     */
    @Test
    void columnLookups() throws Exception {
        int worldCount = 24;
        var fixtures = new ArrayList<RecordReceiveFixture>();
        try (var services = new RecordConsumerServices()) {
            var worlds = new CompensatedWorld[worldCount];
            List<Long2ObjectMap<CachedChunk>> maps = new ArrayList<>();
            for (int w = 0; w < worldCount; w++) {
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
                fixtures.add(fixture);
                worlds[w] = fixture.player.compensatedWorld;
                for (int x = -10; x <= 10; x++)
                    for (int z = -10; z <= 10; z++) worlds[w].ensureValidationChunkLoaded(x, z);
                maps.add(WorldBookkeepingTest.chunks(worlds[w]));
            }
            var random = new Random(11);
            int ticks = 2000, scans = 27 + 1, entities = 20;
            int[] scanX = new int[ticks * scans], scanZ = new int[ticks * scans];
            int[] blockX = new int[ticks * scans], blockY = new int[ticks * scans], blockZ = new int[ticks * scans];
            int[] entityX = new int[ticks * entities], entityZ = new int[ticks * entities];
            double px = 0, pz = 0;
            int s = 0, e = 0;
            for (int tick = 0; tick < ticks; tick++) {
                px += random.nextGaussian() * 0.3;
                pz += random.nextGaussian() * 0.3;
                int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = 0; dy < 3; dy++)
                        for (int dz = -1; dz <= 1; dz++) {
                            blockX[s] = bx + dx;
                            blockY[s] = 64 + dy;
                            blockZ[s] = bz + dz;
                            scanX[s] = (bx + dx) >> 4;
                            scanZ[s++] = (bz + dz) >> 4;
                        }
                blockX[s] = bx;
                blockY[s] = 63;
                blockZ[s] = bz;
                scanX[s] = bx >> 4;
                scanZ[s++] = bz >> 4;
                for (int entity = 0; entity < 20; entity++) {
                    entityX[e] = (bx + random.nextInt(81) - 40) >> 4;
                    entityZ[e++] = (bz + random.nextInt(81) - 40) >> 4;
                }
            }
            int repeats = 0;
            for (int i = 1; i < s; i++) if (scanX[i] == scanX[i - 1] && scanZ[i] == scanZ[i - 1]) repeats++;
            line("column-lookups scan consecutive-repeat " + (repeats * 100 / s) + "%");
            var world = worlds[0];
            var chunks = maps.get(0);
            var bodies = new LinkedHashMap<String, LongUnaryOperator>();
            bodies.put("column-scan map", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int i = 0; i < scanX.length; i++)
                        seen += chunks.get(CompensatedWorld.chunkPositionToLong(scanX[i], scanZ[i])) == null ? 0 : 1;
                return seen;
            });
            bodies.put("column-scan world", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int i = 0; i < scanX.length; i++) seen += world.getChunk(scanX[i], scanZ[i]) == null ? 0 : 1;
                return seen;
            });
            interleaved(s, bodies);
            bodies.clear();
            bodies.put("block-scan world", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int i = 0; i < blockX.length; i++)
                        seen += world.getBlockStateIdAt(blockX[i], blockY[i], blockZ[i]);
                return seen;
            });
            bodies.put("block-scan peek", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int i = 0; i < blockX.length; i++)
                        seen += world.peekBlockStateIdAt(blockX[i], blockY[i], blockZ[i]);
                return seen;
            });
            interleaved(s, bodies);
            bodies.clear();
            bodies.put("column-entities map", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int i = 0; i < entityX.length; i++)
                        seen += chunks.containsKey(CompensatedWorld.chunkPositionToLong(entityX[i], entityZ[i])) ? 1 : 0;
                return seen;
            });
            bodies.put("column-entities world", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int i = 0; i < entityX.length; i++) seen += world.isChunkLoaded(entityX[i], entityZ[i]) ? 1 : 0;
                return seen;
            });
            interleaved(e, bodies);
            bodies.clear();
            bodies.put("column-cold map", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int tick = 0; tick < ticks; tick++)
                        for (int w = 0; w < worldCount; w++) {
                            var map = maps.get(w);
                            for (int i = tick * scans; i < tick * scans + scans; i++)
                                seen += map.get(CompensatedWorld.chunkPositionToLong(scanX[i], scanZ[i])) == null ? 0 : 1;
                        }
                return seen;
            });
            bodies.put("column-cold world", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    for (int tick = 0; tick < ticks; tick++)
                        for (int w = 0; w < worldCount; w++) {
                            var each = worlds[w];
                            for (int i = tick * scans; i < tick * scans + scans; i++)
                                seen += each.getChunk(scanX[i], scanZ[i]) == null ? 0 : 1;
                        }
                return seen;
            });
            interleaved(s * worldCount, bodies);
        } finally {
            for (var fixture : fixtures) fixture.close();
        }
    }

    /**
     * The client tick end's reintern pass with 24 sections written within the last five seconds, none stable yet. The
     * scan body clears the bound first, so every pass walks the pending sections as each did before the bound.
     */
    @Test
    void reinternPasses() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var world = fixture.player.compensatedWorld;
            int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int x = 0; x < 3; x++) for (int z = 0; z < 2; z++) world.ensureValidationChunkLoaded(x, z);
            for (int x = 0; x < 3; x++)
                for (int z = 0; z < 2; z++)
                    for (int y = 0; y < 64; y += 16) world.applyBlockChangeRawDANGER(x * 16, y, z * 16, stone);
            var pending = (Map<?, ?>) WorldBookkeepingTest.field(world, "pendingReinternSections");
            line("reinterns pending " + pending.size());
            var pass = MethodHandles.privateLookupIn(CompensatedWorld.class, MethodHandles.lookup())
                    .findVirtual(CompensatedWorld.class, "processPendingReinterns", MethodType.methodType(void.class));
            var known = MethodHandles.privateLookupIn(CompensatedWorld.class, MethodHandles.lookup())
                    .findVarHandle(CompensatedWorld.class, "earliestPendingMutatedKnown", boolean.class);
            var sections = new ArrayList<CachedSection>();
            for (Object state : pending.values()) sections.add((CachedSection) WorldBookkeepingTest.field(state, "section"));
            var bodies = new LinkedHashMap<String, LongUnaryOperator>();
            bodies.put("reinterns scan", units -> {
                try {
                    refresh(sections, known, world);
                    for (long unit = 0; unit < units; unit++) {
                        known.set(world, false);
                        pass.invokeExact(world);
                    }
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
                return pending.size();
            });
            bodies.put("reinterns bound", units -> {
                try {
                    refresh(sections, known, world);
                    for (long unit = 0; unit < units; unit++) pass.invokeExact(world);
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
                return pending.size();
            });
            interleaved(1, bodies);
            if (pending.size() != 24) throw new IllegalStateException("a section went stable: " + pending.size());
        }
    }

    /** Every pending write lands now, so none goes stable within a rep; the next pass sets the bound afresh. */
    private static void refresh(List<CachedSection> sections, VarHandle known, CompensatedWorld world) {
        long now = System.nanoTime();
        for (CachedSection section : sections) section.setLastMutatedNanos(now);
        known.set(world, false);
    }

    /**
     * The client tick end's world state pass and the two shulker shove list builds of a movement packet, with no
     * recent change, open shulker box or block entity. The removed-work body adds back what the empty guards skip.
     */
    @Test
    void idleTicks() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var world = fixture.player.compensatedWorld;
            var collisions = (Long2IntOpenHashMap) WorldBookkeepingTest.field(world, "recentClientCollisionChanges");
            var fluids = (Long2IntOpenHashMap) WorldBookkeepingTest.field(world, "recentClientFluidChanges");
            var entities = (CompensatedBlockEntities) WorldBookkeepingTest.field(world, "clientBlockEntities");
            var bodies = new LinkedHashMap<String, LongUnaryOperator>();
            bodies.put("idle-ticks removed-work", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    world.removeInvalidPistonLikeStuff();
                    for (var iterator = collisions.long2IntEntrySet().fastIterator(); iterator.hasNext(); ) seen++;
                    for (var iterator = fluids.long2IntEntrySet().fastIterator(); iterator.hasNext(); ) seen++;
                    world.openShulkerBoxes.removeIf(ShulkerData::tickIfGuaranteedFinished);
                    world.openShulkerBoxes.removeIf(box -> box.blockPos == null && world.isChunkLoaded(0, 0));
                    for (int read = 0; read < 2; read++) {
                        List<CompensatedBlockEntities.ShulkerShove> list = new ArrayList<>(1);
                        entities.shulkerShoves(true, true, list);
                        seen += list.size();
                    }
                }
                return seen;
            });
            bodies.put("idle-ticks guarded", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) {
                    world.removeInvalidPistonLikeStuff();
                    for (int read = 0; read < 2; read++) {
                        List<CompensatedBlockEntities.ShulkerShove> list = List.of();
                        if (entities.mayShove(true, true)) {
                            list = new ArrayList<>(1);
                            entities.shulkerShoves(true, true, list);
                        }
                        seen += list.size();
                    }
                }
                return seen;
            });
            interleaved(1, bodies);
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
