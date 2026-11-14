package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.ProtocolVersion;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Bytes and thread CPU per mixed biome section: copied values, a stored hit, a miss; CULT_BIOME_BENCH=1. */
@EnabledIfEnvironmentVariable(named = "CULT_BIOME_BENCH", matches = "1")
class SharedBiomeValuesBenchTest {
    private static final int REPS = 9, SECTIONS = 256, MISSES = 32_768;
    static long sink;

    @Test
    void biomeValues() {
        var random = new SplittableRandom(0xB10BL);
        var repeated = new PalettedSection[SECTIONS];
        for (int i = 0; i < SECTIONS; i++) repeated[i] = mixed(random);
        var distinct = new PalettedSection[MISSES];
        for (int i = 0; i < MISSES; i++) distinct[i] = mixed(random);
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        String[] names = {"copied", "stored hit", "miss"};
        long[][] bytes = new long[3][REPS], nanos = new long[3][REPS];
        int[] next = {0};
        for (int rep = -3; rep < REPS; rep++) {
            for (int mode = 0; mode < 3; mode++) {
                int calls = 0;
                long startBytes = bean.getCurrentThreadAllocatedBytes(), startCpu = bean.getCurrentThreadCpuTime();
                for (int loop = 0; loop < 4000; loop++) {
                    for (int i = 0; i < SECTIONS; i++) {
                        int[] values;
                        if (mode == 0) values = SharedBiomeValuesTest.copyingValues(repeated[i]);
                        else if (mode == 1) values = repeated[i].sharedValues();
                        else values = distinct[next[0]++ & MISSES - 1].sharedValues();
                        sink += values[i & 63];
                        calls++;
                    }
                }
                long cpu = bean.getCurrentThreadCpuTime() - startCpu;
                long allocated = bean.getCurrentThreadAllocatedBytes() - startBytes;
                if (rep >= 0) {
                    bytes[mode][rep] = allocated / calls;
                    nanos[mode][rep] = cpu / calls;
                }
            }
        }
        for (int mode = 0; mode < 3; mode++) {
            long[] b = bytes[mode].clone(), n = nanos[mode].clone();
            Arrays.sort(b);
            Arrays.sort(n);
            System.out.printf(
                    "%-11s %6d B/section  %5d ns/section (min %d, max %d)%n",
                    names[mode], b[REPS / 2], n[REPS / 2], n[0], n[REPS - 1]);
        }
        System.out.println("lookups=" + SectionStore.valueLookupCounter.sum() + " hits="
                + SectionStore.valueHitCounter.sum() + " sink=" + sink);
    }

    private static PalettedSection mixed(SplittableRandom random) {
        ByteBuf output = Unpooled.buffer();
        int bits = 1 + random.nextInt(2);
        int entries = 2 + random.nextInt((1 << bits) - 1);
        output.writeByte(bits);
        output.writeByte(entries);
        for (int i = 0; i < entries; i++) output.writeByte(random.nextInt(64));
        int perWord = 64 / bits;
        for (int word = 0; word < (64 + perWord - 1) / perWord; word++) {
            long value = 0;
            for (int i = 0; i < perWord; i++) value |= (long) random.nextInt(entries) << (i * bits);
            output.writeLong(value);
        }
        try {
            return PalettedSection.readCanonicalBiomes(output, ProtocolVersion.V26_3, 64, id -> id, 64);
        } finally {
            output.release();
        }
    }
}
