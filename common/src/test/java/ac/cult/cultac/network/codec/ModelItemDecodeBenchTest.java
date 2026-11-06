package ac.cult.cultac.network.codec;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
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

    private record Sample(int protocol, ModelItemValues values, ByteBuf bytes) {
        SimItemStack decode() {
            return values.item(bytes.duplicate(), false);
        }
    }

    /** One cache per protocol, refilled from empty in recorded order each pass. */
    private static final class Sent {
        private final Map<Integer, ModelItemValues> values = new HashMap<>();

        void reset() {
            values.clear();
        }

        SimItemStack decode(Sample sample) {
            var cached = values.get(sample.protocol());
            if (cached == null) values.put(sample.protocol(), cached = cached(sample.protocol(), new ItemStackCache()));
            return cached.sentItem(sample.bytes().duplicate());
        }
    }

    private static ModelItemValues cached(int protocol, ItemStackCache cache) {
        var version = ProtocolVersion.of(protocol);
        var names = new WireRegistryState(version, () -> null);
        return new ModelItemValues(
                ProtocolCodecs.decoder(), version, names, ItemUtil.modelItems(), cache, names::generation);
    }

    @Test
    void decode() throws IOException {
        var samples = load();
        report("ITEM-DECODE", samples, () -> {}, sample -> sample.decode().count());
    }

    /** Plain and reusing decodes alternate, so drift reaches both alike. */
    @Test
    void sentDecode() throws IOException {
        var samples = load();
        Map<Integer, ModelItemValues> values = new HashMap<>();
        Map<Integer, ItemStackCache> caches = new HashMap<>();
        int reused = 0;
        for (var sample : samples) {
            var cache = caches.computeIfAbsent(sample.protocol(), protocol -> new ItemStackCache());
            var bytes = sample.bytes();
            int index = bytes.readerIndex();
            while ((bytes.getByte(index++) & 0x80) != 0) {}
            if (cache.find(bytes, index) >= 0) reused++;
            values.computeIfAbsent(sample.protocol(), protocol -> cached(protocol, cache))
                    .sentItem(bytes.duplicate());
        }
        System.out.printf(
                "ITEM-SENT reused %d of %d (%.1f%%)%n", reused, samples.size(), 100.0 * reused / samples.size());
        var sent = new Sent();
        for (int round = 0; round < 3; round++) {
            report("ITEM-PLAIN", samples, () -> {}, sample -> sample.decode().count());
            report(
                    "ITEM-SENT",
                    samples,
                    sent::reset,
                    sample -> sent.decode(sample).count());
        }
    }

    /** Heap held by filled caches: recorded order, and stacks packing the most components into the byte bound. */
    @Test
    void retained() throws IOException {
        var samples = load();
        int sets = 20;
        List<Object> held = new ArrayList<>();
        long before = usedHeap();
        for (int set = 0; set < sets; set++) {
            var sent = new Sent();
            for (var sample : samples) sent.decode(sample);
            held.add(sent);
        }
        long typical = usedHeap() - before;
        System.out.printf("ITEM-RETAINED recorded %.0f B per cache%n", (double) typical / sets / 4);
        held.clear();

        int protocol = 774;
        byte[][] packed = packed(protocol, samples);
        before = usedHeap();
        for (int set = 0; set < sets * 4; set++) {
            var values = cached(protocol, new ItemStackCache());
            for (byte[] stack : packed) values.sentItem(Unpooled.wrappedBuffer(stack));
            held.add(values);
        }
        long worst = usedHeap() - before;
        System.out.printf(
                "ITEM-RETAINED packed %d stacks of %d B: %.0f B per cache%n",
                packed.length, packed[0].length, (double) worst / sets / 4);
        Reference.reachabilityFence(held);
    }

    private static long usedHeap() {
        var memory = ManagementFactory.getMemoryMXBean();
        for (int i = 0; i < 4; i++) System.gc();
        return memory.getHeapMemoryUsage().getUsed();
    }

    /** Distinct stacks built from the smallest recorded encoding of each component, as many as fit the bound. */
    private static byte[][] packed(int protocol, List<Sample> samples) {
        var version = ProtocolVersion.of(protocol);
        var names = new WireRegistryState(version, () -> null);
        var plain = new ModelItemValues(ProtocolCodecs.decoder(), version, names, ItemUtil.modelItems());
        Map<String, byte[]> smallest = new HashMap<>();
        for (var sample : samples) {
            if (sample.protocol() != protocol) continue;
            var added = sample.decode().patch().added();
            for (String key : added.keys()) {
                var encoding = added.wireEncoding(key);
                if (encoding == null || !encoding.matches(protocol, names::id)) continue;
                smallest.merge(key, encoding.bytes(), (left, right) -> right.length < left.length ? right : left);
            }
        }
        var keys = new ArrayList<>(smallest.keySet());
        keys.sort((left, right) -> Integer.compare(smallest.get(left).length, smallest.get(right).length));
        int item = names.id("minecraft:item", "minecraft:stone");
        List<String> chosen = new ArrayList<>();
        for (String key : keys) {
            chosen.add(key);
            byte[] stack = pack(names, ItemStackCache.ENTRIES, item, chosen, smallest);
            boolean fits = stack.length <= ItemStackCache.MAX_BYTES;
            try {
                fits &= plain.item(Unpooled.wrappedBuffer(stack), false)
                                .patch()
                                .added()
                                .bundle()
                        == null;
            } catch (RuntimeException e) {
                fits = false;
            }
            if (!fits) chosen.remove(chosen.size() - 1);
        }
        byte[][] stacks = new byte[ItemStackCache.ENTRIES][];
        for (int count = 1; count <= stacks.length; count++)
            stacks[count - 1] = pack(names, count, item, chosen, smallest);
        System.out.printf("ITEM-PACKED %d components%n", chosen.size());
        return stacks;
    }

    private static byte[] pack(
            WireRegistryState names, int count, int item, List<String> keys, Map<String, byte[]> values) {
        ByteBuf output = Unpooled.buffer();
        Wire.writeVarInt(output, count);
        Wire.writeVarInt(output, item);
        Wire.writeVarInt(output, keys.size());
        Wire.writeVarInt(output, 0);
        for (String key : keys) {
            Wire.writeVarInt(output, names.id("minecraft:data_component_type", key));
            output.writeBytes(values.get(key));
        }
        return ByteBufUtil.getBytes(output);
    }

    /** Self-comparison of decoded stacks' component sets, as inventory change resolution does. */
    @Test
    void sameEncodedNbt() throws IOException {
        var samples = load();
        Map<Sample, SimItemStack> stacks = new IdentityHashMap<>();
        for (var sample : samples) stacks.put(sample, sample.decode());
        report("ITEM-SAME", samples, () -> {}, sample -> {
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
            var sample = new Sample(
                    Integer.parseInt(line.substring(0, space)),
                    values,
                    Unpooled.wrappedBuffer(HexFormat.of().parseHex(line.substring(space + 1))));
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

    private static void report(String label, List<Sample> samples, Runnable pass, ToLongFunction<Sample> body) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 3; i++) run(bean, samples, pass, body);
        long[] cpu = new long[REPS], bytes = new long[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long[] result = run(bean, samples, pass, body);
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

    private static long[] run(ThreadMXBean bean, List<Sample> samples, Runnable pass, ToLongFunction<Sample> body) {
        long thread = Thread.currentThread().threadId();
        long allocated = bean.getThreadAllocatedBytes(thread);
        long cpu = bean.getCurrentThreadCpuTime();
        long sink = 0;
        for (int round = 0; round < PASSES; round++) {
            pass.run();
            for (var sample : samples) sink += body.applyAsLong(sample);
        }
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
