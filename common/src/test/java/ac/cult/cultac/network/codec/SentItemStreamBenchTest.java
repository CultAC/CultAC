package ac.cult.cultac.network.codec;

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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation per sent stack over recorded per-connection streams, each connection decoding through a
 * cache of its own; CULT_ITEM_STREAM names a file of {@code I <connection> <version> <ignored> <hex>} lines.
 */
@EnabledIfEnvironmentVariable(named = "CULT_ITEM_STREAM", matches = ".+")
class SentItemStreamBenchTest {
    private static final int REPS = 9;
    private static final int PASSES = 5;

    private record Stream(ProtocolVersion version, List<ByteBuf> stacks) {}

    @Test
    void sentStream() throws IOException {
        var streams = load();
        int items = streams.stream().mapToInt(stream -> stream.stacks().size()).sum();
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 3; i++) run(bean, streams);
        long[] cpu = new long[REPS], bytes = new long[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long[] result = run(bean, streams);
            cpu[rep] = result[0];
            bytes[rep] = result[1];
        }
        Arrays.sort(cpu);
        Arrays.sort(bytes);
        double total = (double) items * PASSES;
        System.out.printf(
                "ITEM-STREAM %d connections %d stacks cpu median %.1f ns/item (min %.1f max %.1f) alloc median %.1f"
                        + " B/item (min %.1f max %.1f)%n",
                streams.size(),
                items,
                cpu[REPS / 2] / total,
                cpu[0] / total,
                cpu[REPS - 1] / total,
                bytes[REPS / 2] / total,
                bytes[0] / total,
                bytes[REPS - 1] / total);
    }

    private static long[] run(ThreadMXBean bean, List<Stream> streams) {
        long thread = Thread.currentThread().threadId();
        long allocated = bean.getThreadAllocatedBytes(thread);
        long cpu = bean.getCurrentThreadCpuTime();
        long sink = 0;
        for (int pass = 0; pass < PASSES; pass++) {
            for (var stream : streams) {
                var names = new WireRegistryState(stream.version(), () -> null);
                var values = new ModelItemValues(
                        ProtocolCodecs.decoder(),
                        stream.version(),
                        names,
                        ItemUtil.modelItems(),
                        new ItemStackCache(),
                        names::generation);
                for (ByteBuf stack : stream.stacks())
                    sink += values.sentItem(stack.duplicate()).count();
            }
        }
        long[] result = {bean.getCurrentThreadCpuTime() - cpu, bean.getThreadAllocatedBytes(thread) - allocated};
        if (sink == 42) System.out.print("");
        return result;
    }

    private static List<Stream> load() throws IOException {
        Map<String, Stream> streams = new LinkedHashMap<>();
        Map<ProtocolVersion, ModelItemValues> plain = new LinkedHashMap<>();
        int rejected = 0;
        for (String line : Files.readAllLines(Path.of(System.getenv("CULT_ITEM_STREAM")))) {
            if (!line.startsWith("I ")) continue;
            String[] fields = line.split(" ");
            var version = ProtocolVersion.valueOf(fields[2]);
            ByteBuf bytes = Unpooled.wrappedBuffer(HexFormat.of().parseHex(fields[4]));
            var values = plain.computeIfAbsent(
                    version,
                    key -> new ModelItemValues(
                            ProtocolCodecs.decoder(),
                            key,
                            new WireRegistryState(key, () -> null),
                            ItemUtil.modelItems()));
            try {
                values.item(bytes.duplicate(), false);
            } catch (RuntimeException e) {
                rejected++;
                continue;
            }
            streams.computeIfAbsent(fields[1], key -> new Stream(version, new ArrayList<>()))
                    .stacks()
                    .add(bytes);
        }
        System.out.printf("ITEM-STREAM rejected %d%n", rejected);
        return new ArrayList<>(streams.values());
    }
}
