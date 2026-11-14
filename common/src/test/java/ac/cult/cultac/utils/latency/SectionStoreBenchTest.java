package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation per multi-entry block section as the 26.x world reader receives it from bytes: a full read
 * without the store, as before it ("base"), against the store's miss (a container no entry holds, which then becomes
 * one), near miss (an entry with the same fingerprint and one other word), hit, and the section pool's hit; then each
 * followed by one block write. Modes interleave rep by rep in one JVM. CULT_BENCH=1 enables it; CULT_BENCH_OUT and
 * CULT_BENCH_LABEL name the output.
 */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class SectionStoreBenchTest {
    private static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final int STATES = DataTables.defaults().registry().stateCount();
    private static final int COUNT = 256;
    private static final int REPS = 9;
    private static final String[] MODES = {
        "base full read",
        "store miss",
        "store near miss",
        "store hit",
        "pool hit",
        "base full read + write",
        "store miss + write",
        "store hit + write",
        "pool hit + write"
    };
    private static Object sink;
    private static long checksum, stamp;

    @Test
    void sections() throws Exception {
        BEAN.setThreadAllocatedMemoryEnabled(true);
        var random = new SplittableRandom(0x5EC5L);
        byte[][] wires = new byte[COUNT][], stamped = new byte[COUNT][];
        SectionPool[] pools = new SectionPool[COUNT];
        for (int i = 0; i < COUNT; i++) {
            wires[i] = mixed(random);
            stamped[i] = wires[i].clone();
            pools[i] = new SectionPool();
            pools[i].internRetained(new CachedSection(PalettedSection.readBlocks(Unpooled.wrappedBuffer(wires[i]))));
        }
        int modes = MODES.length;
        long[][] nanos = new long[modes][REPS], bytes = new long[modes][REPS];
        int[] units = new int[modes];
        for (int warm = 0; warm < 40; warm++)
            for (int mode = 0; mode < modes; mode++) run(mode, wires, stamped, pools, 20);
        for (int mode = 0; mode < modes; mode++) {
            units[mode] = 20;
            while (true) {
                long start = BEAN.getCurrentThreadCpuTime();
                run(mode, wires, stamped, pools, units[mode]);
                if (BEAN.getCurrentThreadCpuTime() - start >= 1_000_000_000L) break;
                units[mode] *= 2;
            }
        }
        for (int rep = 0; rep < REPS; rep++)
            for (int mode = 0; mode < modes; mode++) {
                long allocated = BEAN.getCurrentThreadAllocatedBytes(), start = BEAN.getCurrentThreadCpuTime();
                run(mode, wires, stamped, pools, units[mode]);
                long sections = (long) units[mode] * COUNT;
                nanos[mode][rep] = (BEAN.getCurrentThreadCpuTime() - start) / sections;
                bytes[mode][rep] = (BEAN.getCurrentThreadAllocatedBytes() - allocated) / sections;
            }
        String label = System.getenv().getOrDefault("CULT_BENCH_LABEL", "run");
        String out = System.getenv("CULT_BENCH_OUT");
        var report = new StringBuilder();
        for (int mode = 0; mode < modes; mode++)
            report.append(label)
                    .append(" | ")
                    .append(MODES[mode])
                    .append(" | ns/section ")
                    .append(summary(nanos[mode]))
                    .append(" | B/section ")
                    .append(summary(bytes[mode]))
                    .append(System.lineSeparator());
        report.append(label)
                .append(" | store entries ")
                .append(SectionStore.size())
                .append(" checksum ")
                .append(checksum)
                .append(System.lineSeparator());
        System.out.print(report);
        if (out != null)
            Files.writeString(
                    Path.of(out), report, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Kinds 0-2 read {@code stamped}, given new contents before each read; kinds 3 and 4 read {@code wires}. */
    private static void run(int mode, byte[][] wires, byte[][] stamped, SectionPool[] pools, int units) {
        boolean write = mode >= 5;
        int kind = write ? new int[] {0, 1, 3, 4}[mode - 5] : mode;
        if (kind == 3)
            for (byte[] wire : wires) PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), (SectionPool) null);
        for (int unit = 0; unit < units; unit++)
            for (int i = 0; i < COUNT; i++) {
                byte[] wire = kind <= 2 ? stamped[i] : wires[i];
                if (kind <= 2) restamp(wire, kind == 2);
                ByteBuf input = Unpooled.wrappedBuffer(wire);
                PalettedSection states = switch (kind) {
                    case 0 -> PalettedSection.readBlocks(input);
                    case 4 -> PalettedSection.readBlocks(input, pools[i]);
                    default -> PalettedSection.readBlocks(input, (SectionPool) null);
                };
                var section = CachedSection.received(states, 4096, 0);
                if (write) section.setStateId((int) (stamp & 4095), section.getStateId(0));
                checksum += section.getStateId(4095);
                sink = section;
            }
        if (kind >= 1 && kind <= 2) SectionStore.clear();
    }

    /**
     * Gives the container new contents: its last word, which the fingerprint reads, or with {@code near} a middle word
     * it does not read, so the entry the last pass stored shares the fingerprint and differs in that word.
     */
    private static void restamp(byte[] wire, boolean near) {
        int words = wire.length - 256 * Long.BYTES;
        int word = near ? 130 : 255;
        long codes = 0, next = ++stamp;
        // Codes 0 and 1, which every palette here holds.
        for (int slot = 0; slot < 16; slot++) codes |= (next >>> slot & 1) << (slot * 4);
        LONGS.set(wire, words + word * Long.BYTES, codes);
    }

    /** A 4-bit section as a server writes it: 6-13 states, three quarters the first. */
    private static byte[] mixed(SplittableRandom random) {
        int[] mix = new int[6 + random.nextInt(8)];
        for (int j = 0; j < mix.length; j++) mix[j] = 1 + random.nextInt(STATES - 1);
        int[] states = new int[4096];
        for (int j = 0; j < states.length; j++)
            states[j] = j < mix.length ? mix[j] : random.nextInt(4) == 0 ? mix[random.nextInt(mix.length)] : mix[0];
        var output = Unpooled.buffer();
        try {
            SectionDecodeBenchTest.encode(output, states, 4, 8, STATES, ProtocolVersion.V26_3);
            byte[] bytes = new byte[output.readableBytes()];
            output.readBytes(bytes);
            return bytes;
        } finally {
            output.release();
        }
    }

    private static String summary(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return "median=" + sorted[sorted.length / 2] + " min=" + sorted[0] + " max=" + sorted[sorted.length - 1];
    }
}
