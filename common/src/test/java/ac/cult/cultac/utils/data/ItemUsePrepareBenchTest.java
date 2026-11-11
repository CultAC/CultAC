package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bytes and thread CPU per tick of preparing item use with no use candidate, the way {@link ItemUseInventory#prepare}
 * calls it, against the base form fed a hotbar copy every tick, interleaved; runs only with CULT_ITEM_USE_BENCH set to
 * an output file.
 */
class ItemUsePrepareBenchTest {
    private static final int TICKS = 1024;
    private static final int REPS = 15;
    private static final long REP_NANOS = 150_000_000L;

    private static Object sink;

    @Test
    void bench() throws Exception {
        String out = System.getenv("CULT_ITEM_USE_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        SimItemStack[] storage = new SimItemStack[46];
        for (int i = 0; i < storage.length; i++)
            storage[i] = i % 3 == 0 ? null : ItemUtil.modelItems().stack("minecraft:diamond_sword", 1);
        var state = new ClientItemUseState();
        var base = new BaseClientItemUseState();
        SimItemStack main = storage[37], off = SimItemStack.EMPTY;
        int[] tick = {0};
        Runnable[] paths = {
            () -> {
                for (int i = 0; i < TICKS; i++)
                    sink = base.prepare(++tick[0], 1, ClientVersion.V_1_21_11, main, off, hotbar(storage));
            },
            () -> {
                for (int i = 0; i < TICKS; i++)
                    sink = state.prepare(
                            ++tick[0],
                            1,
                            ClientVersion.V_1_21_11,
                            main,
                            off,
                            state.preparesFromHotbar() ? hotbar(storage) : List.of());
            }
        };
        for (int i = 0; i < 2000; i++) for (Runnable path : paths) path.run();
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        double[][] bytes = new double[2][REPS], nanos = new double[2][REPS];
        for (int rep = 0; rep < REPS; rep++) {
            for (int k = 0; k < 2; k++) {
                int which = (rep + k) & 1;
                long ticks = 0;
                long beginBytes = bean.getThreadAllocatedBytes(thread);
                long begin = bean.getCurrentThreadCpuTime();
                long elapsed;
                do {
                    paths[which].run();
                    ticks += TICKS;
                    elapsed = bean.getCurrentThreadCpuTime() - begin;
                } while (elapsed < REP_NANOS);
                bytes[which][rep] = (double) (bean.getThreadAllocatedBytes(thread) - beginBytes) / ticks;
                nanos[which][rep] = (double) elapsed / ticks;
            }
        }
        String line = "prepare, no candidate | base " + stat(bytes[0]) + " B, " + stat(nanos[0]) + " ns | new "
                + stat(bytes[1]) + " B, " + stat(nanos[1]) + " ns\n";
        Files.writeString(
                Path.of(out), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    /** {@link ItemUseInventory#hotbar} over a storage array. */
    private static List<SimItemStack> hotbar(SimItemStack[] storage) {
        var result = new ArrayList<SimItemStack>(9);
        for (int slot = 0; slot < 9; slot++) {
            SimItemStack stack = storage[36 + slot];
            result.add(stack == null ? SimItemStack.EMPTY : stack);
        }
        return result;
    }

    private static String stat(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.format("%.1f [%.1f-%.1f]", sorted[sorted.length / 2], sorted[0], sorted[sorted.length - 1]);
    }
}
