package ac.cult.cultac.utils.latency;

import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.latency.CompensatedWorld.ClientboundDimensionData;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation per pinned 26.3 chunk packet, from the codec read of a direct frame to the world reader's
 * received sections and biome values: the section bytes copied at the codec and decoded by the reader ("base"), and
 * the sections decoded at the codec and handed to the reader ("new"), each without pools and with pools holding the
 * mixed sections of every other column, interleaved in one JVM. Columns are those of {@link SectionDecodeBenchTest}.
 * CULT_BENCH=1 enables it; CULT_BENCH_OUT and CULT_BENCH_LABEL name the output.
 */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class ModelChunkDecodeBenchTest {
    private static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int COLUMNS = 64;
    private static final int REPS = 9;
    private static final ClientboundDimensionData PLAIN = new ClientboundDimensionData("bench:plain", -64, 320);
    private static final ClientboundDimensionData POOLED = new ClientboundDimensionData("bench:pooled", -64, 320);
    private static Object sink;
    private static long checksum;

    @Test
    void packets() throws Exception {
        BEAN.setThreadAllocatedMemoryEnabled(true);
        var random = new SplittableRandom(0xC01L);
        ByteBuf[] frames = new ByteBuf[COLUMNS];
        for (int i = 0; i < COLUMNS; i++) {
            byte[] column =
                    SectionDecodeBenchTest.column(new SplittableRandom(random.nextLong()), ProtocolVersion.V26_3);
            byte[] packet = ModelChunkSectionsTest.packet(random, column, i, true);
            frames[i] =
                    PooledByteBufAllocator.DEFAULT.directBuffer(packet.length).writeBytes(packet);
            if (i % 2 == 0) ModelChunkSectionsTest.populate(POOLED.dimension(), i, column, 1);
        }
        String[] names = {"chunk base", "chunk new", "chunk-pool base", "chunk-pool new"};
        long[][] nanos = new long[names.length][REPS], bytes = new long[names.length][REPS];
        int[] units = new int[names.length];
        try {
            for (int warm = 0; warm < 30; warm++) for (int mode = 0; mode < names.length; mode++) run(mode, frames, 50);
            for (int mode = 0; mode < names.length; mode++) {
                units[mode] = 50;
                while (true) {
                    long start = BEAN.getCurrentThreadCpuTime();
                    run(mode, frames, units[mode]);
                    if (BEAN.getCurrentThreadCpuTime() - start >= 500_000_000L) break;
                    units[mode] *= 2;
                }
            }
            for (int rep = 0; rep < REPS; rep++)
                for (int mode = 0; mode < names.length; mode++) {
                    long allocated = BEAN.getCurrentThreadAllocatedBytes(), start = BEAN.getCurrentThreadCpuTime();
                    run(mode, frames, units[mode]);
                    long packets = (long) units[mode] * COLUMNS;
                    nanos[mode][rep] = (BEAN.getCurrentThreadCpuTime() - start) / packets;
                    bytes[mode][rep] = (BEAN.getCurrentThreadAllocatedBytes() - allocated) / packets;
                }
        } finally {
            for (ByteBuf frame : frames) frame.release();
        }
        String label = System.getenv().getOrDefault("CULT_BENCH_LABEL", "run");
        String out = System.getenv("CULT_BENCH_OUT");
        var report = new StringBuilder();
        for (int mode = 0; mode < names.length; mode++)
            report.append(label)
                    .append(' ')
                    .append(names[mode])
                    .append(" ns/packet ")
                    .append(summary(nanos[mode]))
                    .append(" | B/packet ")
                    .append(summary(bytes[mode]))
                    .append(System.lineSeparator());
        report.append(label).append(" checksum ").append(checksum).append(System.lineSeparator());
        System.out.print(report);
        if (out != null)
            Files.writeString(
                    Path.of(out), report, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void run(int mode, ByteBuf[] frames, int units) {
        var dimension = mode < 2 ? PLAIN : POOLED;
        boolean decoded = mode % 2 == 1;
        for (int unit = 0; unit < units; unit++)
            for (ByteBuf frame : frames) {
                var chunk = ModelChunkSectionsTest.read(frame.readerIndex(0), decoded ? dimension : null);
                received(chunk, dimension);
            }
    }

    /** The section loop of the 26.x world reader, both branches. */
    private static void received(WorldPackets.Chunk packet, ClientboundDimensionData dimension) {
        var chunks = new CachedSection[dimension.sectionCount()];
        int[][] biomes = new int[chunks.length][];
        if (packet.decodedSections() != null) {
            for (int i = 0; i < chunks.length; i++) {
                var section = packet.decodedSections().get(i);
                chunks[i] = CachedSection.received(
                        section.takeBlocks(), section.nonEmptyBlockCount(), section.knownFluidCount());
                int biome = section.uniformBiome();
                biomes[i] = biome < 0 ? section.copyBiomes() : CachedChunk.uniformBiomes(biome);
            }
        } else {
            var bytes = Unpooled.wrappedBuffer(packet.sections());
            try {
                for (int i = 0; i < chunks.length; i++) {
                    int nonEmpty = bytes.readShort();
                    int fluids = bytes.readShort();
                    var pool = SectionPool.existing(dimension.dimension(), packet.x(), i, packet.z());
                    chunks[i] = CachedSection.received(PalettedSection.readBlocks(bytes, pool), nonEmpty, fluids);
                    biomes[i] = PalettedSection.readBiomeValues(bytes, ModelChunkSectionsTest.BIOMES);
                }
            } finally {
                bytes.release();
            }
        }
        checksum += chunks[chunks.length - 1].getStateId(4095) + biomes[chunks.length - 1][63];
        sink = chunks;
        sink = biomes;
    }

    private static String summary(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return "median=" + sorted[sorted.length / 2] + " min=" + sorted[0] + " max=" + sorted[sorted.length - 1];
    }
}
