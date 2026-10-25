package ac.cult.cultac.network.codec;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation per item over recorded stacks; CULT_ITEM_BENCH names a file of {@code <protocol> <hex>}
 * lines.
 */
@EnabledIfEnvironmentVariable(named = "CULT_ITEM_BENCH", matches = ".+")
class ModelItemDecodeBenchTest {
    private static final int REPS = 9;
    private static final int PASSES = 5;

    private record Sample(ModelItemValues values, ByteBuf bytes) {
        SimItemStack decode() {
            return values.item(bytes.duplicate(), false);
        }
    }

    @Test
    void decode() throws IOException {
        var samples = load();
        report("ITEM-DECODE", samples, sample -> sample.decode().count());
    }

    /** Self-comparison of decoded stacks' component sets, as inventory change resolution does. */
    @Test
    void sameEncodedNbt() throws IOException {
        var samples = load();
        Map<Sample, SimItemStack> stacks = new IdentityHashMap<>();
        for (var sample : samples) stacks.put(sample, sample.decode());
        report("ITEM-SAME", samples, sample -> {
            var stack = stacks.get(sample);
            return (stack.capturedPrototype().sameEncodedNbt(stack.capturedPrototype()) ? 1 : 0)
                    + (stack.patch().added().sameEncodedNbt(stack.patch().added()) ? 1 : 0);
        });
    }

    private static List<Sample> load() throws IOException {
        Map<Integer, ModelItemValues> decoders = new HashMap<>();
        List<Sample> samples = new ArrayList<>();
        int rejected = 0;
        for (String line : Files.readAllLines(Path.of(System.getenv("CULT_ITEM_BENCH")))) {
            int space = line.indexOf(' ');
            var values = decoders.computeIfAbsent(Integer.parseInt(line.substring(0, space)), protocol -> {
                var version = ProtocolVersion.of(protocol);
                return new ModelItemValues(
                        ProtocolCodecs.decoder(),
                        version,
                        new WireRegistryState(version, () -> null),
                        ItemUtil.modelItems());
            });
            var sample =
                    new Sample(values, Unpooled.wrappedBuffer(HexFormat.of().parseHex(line.substring(space + 1))));
            try {
                sample.decode();
                samples.add(sample);
            } catch (RuntimeException e) {
                rejected++;
            }
        }
        long checksum = 0;
        for (var sample : samples) checksum = checksum * 31 + digest(sample.decode());
        System.out.printf("ITEM-SAMPLES items %d rejected %d checksum %x%n", samples.size(), rejected, checksum);
        return samples;
    }

    private static void report(String label, List<Sample> samples, ToLongFunction<Sample> body) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 3; i++) run(bean, samples, body);
        long[] cpu = new long[REPS], bytes = new long[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long[] result = run(bean, samples, body);
            cpu[rep] = result[0];
            bytes[rep] = result[1];
        }
        Arrays.sort(cpu);
        Arrays.sort(bytes);
        double items = (double) samples.size() * PASSES;
        System.out.printf(
                "%s cpu median %.1f ns/item (min %.1f max %.1f) alloc median %.1f B/item (min %.1f max %.1f)%n",
                label,
                cpu[REPS / 2] / items,
                cpu[0] / items,
                cpu[REPS - 1] / items,
                bytes[REPS / 2] / items,
                bytes[0] / items,
                bytes[REPS - 1] / items);
    }

    private static long[] run(ThreadMXBean bean, List<Sample> samples, ToLongFunction<Sample> body) {
        long thread = Thread.currentThread().threadId();
        long allocated = bean.getThreadAllocatedBytes(thread);
        long cpu = bean.getCurrentThreadCpuTime();
        long sink = 0;
        for (int pass = 0; pass < PASSES; pass++) for (var sample : samples) sink += body.applyAsLong(sample);
        long[] result = {bean.getCurrentThreadCpuTime() - cpu, bean.getThreadAllocatedBytes(thread) - allocated};
        if (sink == 42) System.out.print("");
        return result;
    }

    private static long digest(SimItemStack stack) {
        if (stack.isEmpty()) return 0;
        long hash = stack.itemKey().hashCode() * 31L + stack.count();
        var added = stack.patch().added();
        for (String key : added.keys().stream().sorted().toList()) {
            var encoding = added.wireEncoding(key);
            hash = hash * 31 + key.hashCode();
            if (encoding != null) hash = hash * 31 + Arrays.hashCode(encoding.bytes());
        }
        hash = hash * 31 + stack.patch().hashCode();
        var raw = stack.wirePatch();
        return raw == null ? hash : hash * 31 + Arrays.hashCode(raw.bytes());
    }
}
