package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.protocol.ProtocolVersion;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation per global container of the copying read then {@link PalettedSection#remap} against the
 * mapper-taking read, in alternating batches of at least 300 ms CPU each. Appends its report to the file named by
 * CULTAC_PALETTE_REMAP_BENCH.
 */
@EnabledIfEnvironmentVariable(named = "CULTAC_PALETTE_REMAP_BENCH", matches = ".+")
class PalettedSectionGlobalRemapBenchTest {
    private static final long BATCH_CPU_NANOS = 300_000_000L;
    private static final int SAMPLES = 9;

    @Test
    void copyRemapAgainstReadAndRemap() throws IOException {
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var report = new StringBuilder();
        report.append(workload(bean, "blocks 30000->29000 (15 bits)", true, 4096, 30_000, 29_000));
        report.append(workload(bean, "biomes 100->90 (7 bits)", false, 64, 100, 90));
        Files.writeString(
                Path.of(System.getenv("CULTAC_PALETTE_REMAP_BENCH")),
                report,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private String workload(ThreadMXBean bean, String name, boolean blocks, int size, int source, int target) {
        var random = new SplittableRandom(0xBE7CL + source);
        byte[][] wires = new byte[32][];
        for (int i = 0; i < wires.length; i++)
            wires[i] = PalettedSectionGlobalRemapTest.cleanGlobalWire(random, size, source, ProtocolVersion.V26_3);
        int[] table = new int[source];
        for (int id = 0; id < source; id++) table[id] = random.nextInt(target);
        IntUnaryOperator mapper = id -> table[id];
        for (byte[] wire : wires) {
            assertArrayEquals(
                    written(run(false, blocks, wire, source, mapper, target)),
                    written(run(true, blocks, wire, source, mapper, target)));
        }

        long thread = Thread.currentThread().threadId();
        int operations = 1000;
        for (int warmup = 0; warmup < 4; warmup++) {
            batch(false, blocks, wires, operations, source, mapper, target);
            batch(true, blocks, wires, operations, source, mapper, target);
        }
        while (true) {
            long start = bean.getThreadCpuTime(thread);
            batch(false, blocks, wires, operations, source, mapper, target);
            long middle = bean.getThreadCpuTime(thread);
            batch(true, blocks, wires, operations, source, mapper, target);
            long end = bean.getThreadCpuTime(thread);
            if (Math.min(middle - start, end - middle) >= BATCH_CPU_NANOS) break;
            operations *= 2;
        }
        double[][] cpu = new double[2][SAMPLES], alloc = new double[2][SAMPLES];
        long[] checksums = new long[2];
        for (int sample = 0; sample < SAMPLES; sample++) {
            for (int side = 0; side < 2; side++) {
                boolean optimized = (side == 0) == (sample % 2 == 1);
                int index = optimized ? 1 : 0;
                long allocated = bean.getThreadAllocatedBytes(thread);
                long start = bean.getThreadCpuTime(thread);
                long checksum = batch(optimized, blocks, wires, operations, source, mapper, target);
                cpu[index][sample] = (double) (bean.getThreadCpuTime(thread) - start) / operations;
                alloc[index][sample] = (double) (bean.getThreadAllocatedBytes(thread) - allocated) / operations;
                checksums[index] += checksum;
            }
        }
        assertEquals(checksums[0], checksums[1], name);
        return String.format(
                "%s ops/batch=%d samples=%d%n  copy-remap   cpu ns/op median %.0f [%s] alloc B/op median %.1f%n"
                        + "  read-remap   cpu ns/op median %.0f [%s] alloc B/op median %.1f%n"
                        + "  cpu ratio %.3f alloc ratio %.3f checksum %d%n",
                name,
                operations,
                SAMPLES,
                median(cpu[0]),
                range(cpu[0]),
                median(alloc[0]),
                median(cpu[1]),
                range(cpu[1]),
                median(alloc[1]),
                median(cpu[1]) / median(cpu[0]),
                median(alloc[1]) / median(alloc[0]),
                checksums[0]);
    }

    private static long batch(
            boolean optimized,
            boolean blocks,
            byte[][] wires,
            int operations,
            int source,
            IntUnaryOperator mapper,
            int target) {
        long checksum = 0;
        for (int i = 0; i < operations; i++) {
            var section = run(optimized, blocks, wires[i % wires.length], source, mapper, target);
            checksum = checksum * 31 + section.get((i * 31) & (blocks ? 4095 : 63));
        }
        return checksum;
    }

    private static PalettedSection run(
            boolean optimized, boolean blocks, byte[] wire, int source, IntUnaryOperator mapper, int target) {
        ByteBuf input = Unpooled.wrappedBuffer(wire);
        var version = ProtocolVersion.V26_3;
        if (optimized)
            return blocks
                    ? PalettedSection.readBlocks(input, version, source, mapper, target)
                    : PalettedSection.readBiomes(input, version, source, mapper, target);
        return (blocks
                        ? PalettedSection.readBlocks(input, version, source)
                        : PalettedSection.readBiomes(input, version, source))
                .remap(mapper, target);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String range(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.format("%.0f..%.0f", sorted[0], sorted[sorted.length - 1]);
    }

    private static byte[] written(PalettedSection section) {
        ByteBuf output = Unpooled.buffer();
        section.write(output);
        byte[] bytes = new byte[output.readableBytes()];
        output.readBytes(bytes);
        return bytes;
    }
}
