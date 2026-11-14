package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.packet.SectionSnapshot;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Bytes and thread CPU per operation of the chunk reader's per-column steps: a column's ticker filter over no
 * candidates, and a decoded single-entry section taken and received; CULT_BENCH=1.
 */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class WorldTailOpsBenchTest {
    private static final int REPS = 9, OPS = 200_000;
    private static final int STATES = DataTables.defaults().registry().stateCount();
    static long sink;

    @Test
    void perColumnSteps() {
        List<BlockPos> candidates = List.of();
        var air = snapshot(0);
        var stone = snapshot(1);
        String[] names = {"ticker stream", "ticker guarded", "take air", "take stone"};
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[][] bytes = new long[names.length][REPS], nanos = new long[names.length][REPS];
        for (int rep = -20; rep < REPS; rep++) {
            for (int mode = 0; mode < names.length; mode++) {
                long startBytes = bean.getCurrentThreadAllocatedBytes(), startCpu = bean.getCurrentThreadCpuTime();
                for (int op = 0; op < OPS; op++) {
                    int y = op;
                    Object kept = switch (mode) {
                        case 0 ->
                            candidates.stream()
                                    .filter(position -> position.getY() == y)
                                    .toList();
                        case 1 ->
                            candidates.isEmpty()
                                    ? List.<BlockPos>of()
                                    : candidates.stream()
                                            .filter(position -> position.getY() == y)
                                            .toList();
                        case 2 -> CachedSection.received(air.takeBlocks(), 0, 0);
                        default -> CachedSection.received(stone.takeBlocks(), 4096, 0);
                    };
                    sink += System.identityHashCode(kept) & 1;
                }
                long cpu = bean.getCurrentThreadCpuTime() - startCpu;
                long allocated = bean.getCurrentThreadAllocatedBytes() - startBytes;
                if (rep >= 0) {
                    bytes[mode][rep] = allocated / OPS;
                    nanos[mode][rep] = cpu / OPS;
                }
            }
        }
        for (int mode = 0; mode < names.length; mode++) {
            long[] b = bytes[mode].clone(), n = nanos[mode].clone();
            Arrays.sort(b);
            Arrays.sort(n);
            System.out.printf(
                    "%-15s %5d B/op  %5d ns/op (min %d, max %d)%n",
                    names[mode], b[REPS / 2], n[REPS / 2], n[0], n[REPS - 1]);
        }
    }

    /** A pinned 26.3 section of {@code state} everywhere, decoded as the observed chunk codec decodes it. */
    private static SectionSnapshot snapshot(int state) {
        var wire = Unpooled.buffer();
        wire.writeShort(state == 0 ? 0 : 4096).writeShort(0);
        wire.writeByte(0).writeByte(state);
        wire.writeByte(0).writeByte(0);
        IntUnaryOperator identity = IntUnaryOperator.identity();
        return SectionSnapshot.read(wire, ProtocolVersion.V26_3, STATES, STATES, 64, 64, identity, identity);
    }
}
