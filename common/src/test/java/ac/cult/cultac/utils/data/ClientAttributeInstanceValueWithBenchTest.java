package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation of movement_speed valueWith, interleaving the copy-and-recalculate body with the current
 * one; CULT_ATTRIBUTE_BENCH=1 enables it.
 */
@EnabledIfEnvironmentVariable(named = "CULT_ATTRIBUTE_BENCH", matches = "1")
class ClientAttributeInstanceValueWithBenchTest {
    private static final int REPS = 9;
    private static final int CALLS = 10_000_000;
    private static final Set<String> EXCLUDED = Set.of(CompensatedEntities.SPRINTING_SPEED_MODIFIER.id());
    private static final AttributeModifier SPRINTING = CompensatedEntities.SPRINTING_SPEED_MODIFIER;
    private static final AttributeModifier NOT_SPRINTING =
            new AttributeModifier(SPRINTING.id(), 0.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    static double sink;

    /** Server snapshots a sprinting player sees: none, the sprint modifier, plus speed, plus powder snow. */
    private static final List<List<AttributeModifier>> STATES = List.of(
            List.of(),
            List.of(SPRINTING),
            List.of(
                    SPRINTING,
                    new AttributeModifier(
                            "minecraft:effect.speed", 0.4F, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)),
            List.of(
                    new AttributeModifier(
                            CompensatedEntities.SNOW_MODIFIER_ID, -0.05F, AttributeModifier.Operation.ADD_VALUE),
                    SPRINTING));

    interface Call {
        double call(int state, AttributeModifier probe);
    }

    @Test
    void valueWith() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var instances = new ClientAttributeInstance[STATES.size()];
        List<LinkedHashMap<String, AttributeModifier>> maps = new ArrayList<>();
        for (int i = 0; i < instances.length; i++) {
            instances[i] = new ClientAttributeInstance(0.1F, 0.0D, 1024.0D, EXCLUDED);
            instances[i].replace(0.1F, STATES.get(i));
            var map = new LinkedHashMap<String, AttributeModifier>();
            for (AttributeModifier modifier : STATES.get(i)) map.put(modifier.id(), modifier);
            maps.add(map);
        }
        Call base = (state, probe) ->
                ClientAttributeInstanceValueWithTest.expected(0.1F, maps.get(state), EXCLUDED, 0.0D, 1024.0D, probe);
        Call branch = (state, probe) -> instances[state].valueWith(probe);
        for (int i = 0; i < 3; i++) {
            run(bean, base);
            run(bean, branch);
        }
        long[][] cpu = new long[2][REPS], bytes = new long[2][REPS];
        for (int rep = 0; rep < REPS; rep++) {
            for (int which = 0; which < 2; which++) {
                int index = (rep & 1) == 0 ? which : 1 - which;
                long[] result = run(bean, index == 0 ? base : branch);
                cpu[index][rep] = result[0];
                bytes[index][rep] = result[1];
            }
        }
        report("base", cpu[0], bytes[0]);
        report("branch", cpu[1], bytes[1]);
    }

    private static long[] run(ThreadMXBean bean, Call call) {
        long thread = Thread.currentThread().getId();
        long allocated = bean.getThreadAllocatedBytes(thread);
        long cpu = bean.getCurrentThreadCpuTime();
        double total = 0;
        for (int i = 0; i < CALLS; i++) total += call.call(i & 3, (i & 4) == 0 ? SPRINTING : NOT_SPRINTING);
        long spent = bean.getCurrentThreadCpuTime() - cpu;
        long garbage = bean.getThreadAllocatedBytes(thread) - allocated;
        sink += total;
        return new long[] {spent, garbage};
    }

    private static void report(String name, long[] cpu, long[] bytes) {
        long[] ns = cpu.clone(), b = bytes.clone();
        Arrays.sort(ns);
        Arrays.sort(b);
        System.out.printf(
                "VALUEWITH %s cpu median %.1f ns/call (min %.1f max %.1f) alloc median %.1f B/call (min %.1f max %.1f)%n",
                name,
                ns[REPS / 2] / (double) CALLS,
                ns[0] / (double) CALLS,
                ns[REPS - 1] / (double) CALLS,
                b[REPS / 2] / (double) CALLS,
                b[0] / (double) CALLS,
                b[REPS - 1] / (double) CALLS);
    }
}
