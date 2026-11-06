package ac.cult.cultac.utils.inventory;

import ac.cult.blocksim.data.ItemComponents;
import com.google.gson.JsonParser;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Thread CPU and allocation per enchantment and tool read of one held stack; CULT_COMPONENTS_BENCH=1 enables it. */
@EnabledIfEnvironmentVariable(named = "CULT_COMPONENTS_BENCH", matches = "1")
class ItemComponentsReadBenchTest {
    private static final int REPS = 9;
    private static final int READS = 2_000_000;
    static int sink;

    @Test
    void reads() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var components = ItemUtil.modelItems()
                .stack("minecraft:diamond_pickaxe", 1)
                .components()
                .with(
                        "minecraft:enchantments",
                        JsonParser.parseString("{\"minecraft:unbreaking\":2,\"minecraft:efficiency\":3}"));
        long[] cpu = new long[REPS], bytes = new long[REPS];
        for (int rep = -3; rep < REPS; rep++) {
            long thread = Thread.currentThread().threadId();
            long allocated = bean.getThreadAllocatedBytes(thread);
            long start = bean.getCurrentThreadCpuTime();
            int total = 0;
            for (int i = 0; i < READS; i++)
                total += ItemComponents.enchantments(components).size()
                        + ItemComponents.tool(components).rules().size();
            sink += total;
            if (rep < 0) continue;
            cpu[rep] = bean.getCurrentThreadCpuTime() - start;
            bytes[rep] = bean.getThreadAllocatedBytes(thread) - allocated;
        }
        Arrays.sort(cpu);
        Arrays.sort(bytes);
        System.out.printf(
                "COMPONENT-READS cpu median %.1f ns/read (min %.1f max %.1f) alloc median %.1f B/read (min %.1f max"
                        + " %.1f)%n",
                cpu[REPS / 2] / (double) READS,
                cpu[0] / (double) READS,
                cpu[REPS - 1] / (double) READS,
                bytes[REPS / 2] / (double) READS,
                bytes[0] / (double) READS,
                bytes[REPS - 1] / (double) READS);
    }
}
