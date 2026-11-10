package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.packet.SectionSnapshot;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.SplittableRandom;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation per chunk column of section decoding as the 26.x world reader runs it: received sections
 * and biome values from decoded snapshots (1.21.4 and 26.1 framing) and from the raw 26.3 bytes, each without a pool
 * and with a pool per section position that holds the mixed sections of every other column. Each mode runs the
 * copying reads ("base") and the canonical reads ("new"), interleaved in one JVM. Each column holds 18 air sections,
 * one stone section and five mixed sections; 20 of its 24 biome containers hold one biome. CULT_BENCH=1 enables it;
 * CULT_BENCH_OUT and CULT_BENCH_LABEL name the output.
 */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class SectionDecodeBenchTest {
    private static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int STATES = DataTables.defaults().registry().stateCount();
    private static final int BIOMES = 65;
    private static final int SECTIONS = 24;
    private static final int COLUMNS = 64;
    private static final int REPS = 9;
    private static final ProtocolVersion[] FRAMINGS = {
        ProtocolVersion.V1_21_4, ProtocolVersion.V26_1, ProtocolVersion.V26_3
    };
    private static final MethodHandle CONSTRUCTED = constructed();
    private static Object sink;
    private static long checksum;

    @Test
    void columns() throws Exception {
        BEAN.setThreadAllocatedMemoryEnabled(true);
        var random = new SplittableRandom(0xC01L);
        byte[][][] wires = new byte[FRAMINGS.length][COLUMNS][];
        SectionPool[][][] pools = new SectionPool[FRAMINGS.length][COLUMNS][];
        for (int i = 0; i < COLUMNS; i++) {
            long seed = random.nextLong();
            for (int framing = 0; framing < FRAMINGS.length; framing++) {
                wires[framing][i] = column(new SplittableRandom(seed), FRAMINGS[framing]);
                pools[framing][i] = pools(wires[framing][i], FRAMINGS[framing], i % 2 == 0);
            }
        }
        String[] paths = {"decoded-1.21.4", "decoded-26.1", "raw-26.3"};
        int modes = paths.length * 4;
        var names = new String[modes];
        for (int mode = 0; mode < modes; mode++)
            names[mode] = paths[mode / 4] + (mode % 4 / 2 == 0 ? "" : "-pool") + (mode % 2 == 0 ? " base" : " new");
        long[][] nanos = new long[modes][REPS], bytes = new long[modes][REPS];
        int[] units = new int[modes];
        for (int warm = 0; warm < 30; warm++) for (int mode = 0; mode < modes; mode++) run(mode, wires, pools, 100);
        for (int mode = 0; mode < modes; mode++) {
            units[mode] = 100;
            while (true) {
                long start = BEAN.getCurrentThreadCpuTime();
                run(mode, wires, pools, units[mode]);
                if (BEAN.getCurrentThreadCpuTime() - start >= 500_000_000L) break;
                units[mode] *= 2;
            }
        }
        for (int rep = 0; rep < REPS; rep++)
            for (int mode = 0; mode < modes; mode++) {
                long allocated = BEAN.getCurrentThreadAllocatedBytes(), start = BEAN.getCurrentThreadCpuTime();
                run(mode, wires, pools, units[mode]);
                long columns = (long) units[mode] * COLUMNS;
                nanos[mode][rep] = (BEAN.getCurrentThreadCpuTime() - start) / columns;
                bytes[mode][rep] = (BEAN.getCurrentThreadAllocatedBytes() - allocated) / columns;
            }
        String label = System.getenv().getOrDefault("CULT_BENCH_LABEL", "run");
        String out = System.getenv("CULT_BENCH_OUT");
        var report = new StringBuilder();
        for (int mode = 0; mode < modes; mode++)
            report.append(label)
                    .append(' ')
                    .append(names[mode])
                    .append(" ns/column ")
                    .append(summary(nanos[mode]))
                    .append(" | B/column ")
                    .append(summary(bytes[mode]))
                    .append(System.lineSeparator());
        System.out.print(report);
        if (out != null)
            Files.writeString(
                    Path.of(out), report, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void run(int mode, byte[][][] wires, SectionPool[][][] pools, int units) {
        int framing = mode / 4;
        boolean pooled = mode % 4 / 2 == 1, canonical = mode % 2 == 1;
        for (int unit = 0; unit < units; unit++)
            for (int column = 0; column < COLUMNS; column++) {
                byte[] wire = wires[framing][column];
                SectionPool[] columnPools = pooled ? pools[framing][column] : null;
                if (framing == 2) rawColumn(wire, columnPools, canonical);
                else decodedColumn(wire, FRAMINGS[framing], columnPools, canonical);
            }
    }

    private static void decodedColumn(byte[] wire, ProtocolVersion version, SectionPool[] pools, boolean canonical) {
        var input = Unpooled.wrappedBuffer(wire);
        var chunks = new CachedSection[SECTIONS];
        int[][] biomes = new int[SECTIONS][];
        for (int i = 0; i < SECTIONS; i++) {
            var pool = pools == null ? null : pools[i];
            if (canonical) {
                var section = SectionSnapshot.read(
                        input,
                        version,
                        STATES,
                        STATES,
                        BIOMES,
                        BIOMES,
                        IntUnaryOperator.identity(),
                        IntUnaryOperator.identity(),
                        pool);
                chunks[i] = CachedSection.received(
                        section.copyBlocks(), section.nonEmptyBlockCount(), section.knownFluidCount());
                int biome = section.uniformBiome();
                biomes[i] = biome < 0 ? section.copyBiomes() : CachedChunk.uniformBiomes(biome);
            } else {
                var section = baseSnapshot(input, version, pool);
                chunks[i] = constructed(section.blocks().copyOnWrite(), section.nonEmpty(), section.knownFluids());
                int biome = section.biomes().singleValue();
                biomes[i] = biome < 0 ? values(section.biomes()) : CachedChunk.uniformBiomes(biome);
            }
        }
        consume(chunks, biomes);
    }

    /** The section snapshot as the copying block and biome reads build it. */
    private record BaseSnapshot(
            short nonEmpty, short fluids, int knownFluids, PalettedSection blocks, PalettedSection biomes) {}

    private static BaseSnapshot baseSnapshot(ByteBuf input, ProtocolVersion version, SectionPool pool) {
        short nonEmpty = input.readShort();
        boolean wireFluids = version.atLeast(ProtocolVersion.V26_1);
        short fluids = wireFluids ? input.readShort() : 0;
        var blocks =
                PalettedSection.readBlocksPooled(input, version, STATES, IntUnaryOperator.identity(), STATES, pool);
        return new BaseSnapshot(
                nonEmpty,
                fluids,
                wireFluids ? fluids : blocks.pooledFluidCount(),
                blocks.states(),
                PalettedSection.readBiomes(input, version, BIOMES, IntUnaryOperator.identity(), BIOMES));
    }

    private static void rawColumn(byte[] wire, SectionPool[] pools, boolean canonical) {
        var input = Unpooled.wrappedBuffer(wire);
        var chunks = new CachedSection[SECTIONS];
        int[][] biomes = new int[SECTIONS][];
        for (int i = 0; i < SECTIONS; i++) {
            int nonEmpty = input.readShort();
            int fluids = input.readShort();
            var blocks = PalettedSection.readBlocks(input, pools == null ? null : pools[i]);
            if (canonical) {
                chunks[i] = CachedSection.received(blocks, nonEmpty, fluids);
                biomes[i] = PalettedSection.readBiomeValues(input, BIOMES);
            } else {
                chunks[i] = constructed(blocks, nonEmpty, fluids);
                var biome = PalettedSection.readBiomes(input, ProtocolVersion.V26_3, BIOMES);
                int single = biome.singleValue();
                biomes[i] = single >= 0 ? CachedChunk.uniformBiomes(single) : values(biome);
            }
        }
        consume(chunks, biomes);
    }

    private static int[] values(PalettedSection section) {
        int[] values = new int[64];
        for (int i = 0; i < values.length; i++) values[i] = section.get(i);
        return values;
    }

    private static CachedSection constructed(PalettedSection states, int nonEmpty, int fluids) {
        try {
            return (CachedSection) CONSTRUCTED.invokeExact(states, nonEmpty, fluids);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static MethodHandle constructed() {
        try {
            return MethodHandles.privateLookupIn(CachedSection.class, MethodHandles.lookup())
                    .findConstructor(
                            CachedSection.class,
                            MethodType.methodType(void.class, PalettedSection.class, int.class, int.class));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * A pool per section position; with {@code held}, each pool holds the column's mixed section at its position, as
     * another connection that loaded the same column leaves it.
     */
    private static SectionPool[] pools(byte[] wire, ProtocolVersion version, boolean held) {
        var pools = new SectionPool[SECTIONS];
        var input = Unpooled.wrappedBuffer(wire);
        for (int i = 0; i < SECTIONS; i++) {
            pools[i] = new SectionPool();
            var blocks = baseSnapshot(input, version, null).blocks();
            if (held && blocks.singleValue() < 0) pools[i].internRetained(new CachedSection(blocks));
        }
        return pools;
    }

    private static void consume(CachedSection[] chunks, int[][] biomes) {
        checksum += chunks[SECTIONS - 1].getStateId(4095) + biomes[SECTIONS - 1][63];
        sink = chunks;
        sink = biomes;
    }

    /** One column in {@code version}'s framing, each container as a vanilla server writes it. */
    static byte[] column(SplittableRandom random, ProtocolVersion version) {
        int stone = 1;
        ByteBuf output = Unpooled.buffer();
        try {
            for (int i = 0; i < SECTIONS; i++) {
                int[] states = new int[4096];
                int nonEmpty = 0;
                if (i == 0) Arrays.fill(states, stone);
                else if (i <= 5) {
                    int[] mix = new int[6 + random.nextInt(8)];
                    for (int j = 0; j < mix.length; j++) mix[j] = 1 + random.nextInt(STATES - 1);
                    for (int j = 0; j < states.length; j++)
                        states[j] = random.nextInt(4) == 0 ? mix[random.nextInt(mix.length)] : mix[0];
                }
                for (int state : states) if (state != 0) nonEmpty++;
                output.writeShort(nonEmpty);
                if (version.atLeast(ProtocolVersion.V26_1)) output.writeShort(0);
                encode(output, states, 4, 8, STATES, version);
                int[] biomes = new int[64];
                Arrays.fill(biomes, random.nextInt(BIOMES));
                int other = random.nextInt(BIOMES);
                if (i % 6 == 1) for (int j = 32; j < 64; j++) biomes[j] = other;
                encode(output, biomes, 1, 3, BIOMES, version);
            }
            byte[] bytes = new byte[output.readableBytes()];
            output.readBytes(bytes);
            return bytes;
        } finally {
            output.release();
        }
    }

    /** A paletted container as a vanilla server writes it: first-seen palette at the minimal local width. */
    static void encode(
            ByteBuf out, int[] values, int minimumBits, int maximumBits, int registry, ProtocolVersion version) {
        var ids = new LinkedHashMap<Integer, Integer>();
        for (int value : values) ids.putIfAbsent(value, ids.size());
        int needed = 32 - Integer.numberOfLeadingZeros(ids.size() - 1);
        boolean global = needed > maximumBits;
        int bits = ids.size() == 1
                ? 0
                : global ? 32 - Integer.numberOfLeadingZeros(registry - 1) : Math.max(minimumBits, needed);
        out.writeByte(bits);
        if (bits == 0) Wire.writeVarInt(out, values[0]);
        else if (!global) {
            Wire.writeVarInt(out, ids.size());
            for (int id : ids.keySet()) Wire.writeVarInt(out, id);
        }
        int perWord = bits == 0 ? 1 : 64 / bits;
        int words = bits == 0 ? 0 : (values.length + perWord - 1) / perWord;
        if (!version.atLeast(ProtocolVersion.V1_21_5)) Wire.writeVarInt(out, words);
        for (int word = 0, index = 0; word < words; word++) {
            long packed = 0;
            for (int slot = 0; slot < perWord && index < values.length; slot++, index++)
                packed |= (long) (global ? values[index] : ids.get(values[index])) << (slot * bits);
            out.writeLong(packed);
        }
    }

    private static String summary(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return "median=" + sorted[sorted.length / 2] + " min=" + sorted[0] + " max=" + sorted[sorted.length - 1];
    }
}
