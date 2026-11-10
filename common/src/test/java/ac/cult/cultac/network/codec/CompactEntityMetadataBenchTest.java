package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation per entity data packet, decoded and looked up as the model applies it, with entry objects
 * built one by one and with the readers' entries, interleaved; CULT_METADATA_BENCH=1 enables it.
 */
@EnabledIfEnvironmentVariable(named = "CULT_METADATA_BENCH", matches = "1")
class CompactEntityMetadataBenchTest {
    private static final ProtocolVersion VERSION = ProtocolVersion.V26_3;
    private static final int REPS = 9;
    private static final int PACKETS = 3_000_000;
    /** Indices the model reads for a living mob, then the replication's pose test. */
    private static final int[] LOOKUPS = {0, 10, 6, 9, 16, 16, 8, 9, 10, 16, 17, 18, 5, 9, 8, 15, 6};

    static int sink;

    @Test
    void heldReader() {
        var registries = new WireRegistryState(VERSION, () -> null);
        var held = new ModelEntityMetadataValues(ProtocolCodecs.decoder(), VERSION, registries, name -> null);
        var legacy = new LegacyHeld(registries);
        compare("HELD", packet -> lookups(legacy.read(packet)), packet -> lookups(held.read(packet)));
    }

    @Test
    void readerPerPacket() {
        var registries = new WireRegistryState(VERSION, () -> null);
        compare(
                "PER-PACKET",
                packet -> {
                    new ModelEntityMetadataValues(ProtocolCodecs.decoder(), VERSION, registries, name -> null);
                    return lookups(legacyOnce(registries, packet));
                },
                packet -> lookups(
                        new ModelEntityMetadataValues(ProtocolCodecs.decoder(), VERSION, registries, name -> null)
                                .readOnce(packet, null)));
    }

    private static int lookups(EntityMetadata metadata) {
        List<EntityMetadata.Entry> entries = metadata.packedItems();
        int found = 0;
        for (int index : LOOKUPS) {
            if (WatchableIndexUtil.value(entries, index) != null) found++;
            if (WatchableIndexUtil.has(entries, index)) found++;
        }
        return found + entries.size();
    }

    private static void compare(String label, ToIntFunction<ByteBuf> base, ToIntFunction<ByteBuf> changed) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        ByteBuf[] packets = packets();
        for (int i = 0; i < 3; i++) {
            run(bean, base, packets);
            run(bean, changed, packets);
        }
        long[][] results = new long[4][REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long[] before = run(bean, base, packets), after = run(bean, changed, packets);
            results[0][rep] = before[0];
            results[1][rep] = before[1];
            results[2][rep] = after[0];
            results[3][rep] = after[1];
        }
        print(label + "-BASE", results[0], results[1]);
        print(label + "-NEW", results[2], results[3]);
        for (ByteBuf packet : packets) packet.release();
    }

    private static void print(String label, long[] cpu, long[] bytes) {
        Arrays.sort(cpu);
        Arrays.sort(bytes);
        System.out.printf(
                "METADATA-%s cpu median %.1f ns/packet (min %.1f max %.1f) alloc median %.1f B/packet (min %.1f max"
                        + " %.1f)%n",
                label,
                cpu[REPS / 2] / (double) PACKETS,
                cpu[0] / (double) PACKETS,
                cpu[REPS - 1] / (double) PACKETS,
                bytes[REPS / 2] / (double) PACKETS,
                bytes[0] / (double) PACKETS,
                bytes[REPS - 1] / (double) PACKETS);
    }

    private static long[] run(ThreadMXBean bean, ToIntFunction<ByteBuf> read, ByteBuf[] packets) {
        long thread = Thread.currentThread().getId();
        long allocated = bean.getThreadAllocatedBytes(thread);
        long cpu = bean.getCurrentThreadCpuTime();
        int total = 0;
        for (int i = 0; i < PACKETS; i++) total += read.applyAsInt(packets[i % packets.length].readerIndex(0));
        long spent = bean.getCurrentThreadCpuTime() - cpu;
        long garbage = bean.getThreadAllocatedBytes(thread) - allocated;
        sink += total;
        return new long[] {spent, garbage};
    }

    /** 40 % one entry, 25 % two, 35 % three to seven: flags, pose, health, air, then integers and booleans. */
    private static ByteBuf[] packets() {
        var random = new Random(3);
        ByteBuf[] packets = new ByteBuf[64];
        for (int p = 0; p < packets.length; p++) {
            int roll = random.nextInt(100);
            int entries = roll < 40 ? 1 : roll < 65 ? 2 : 3 + random.nextInt(5);
            ByteBuf input = Unpooled.buffer();
            Wire.writeVarInt(input, 1000 + p);
            for (int i = 0; i < entries; i++) {
                switch (i) {
                    case 0 -> {
                        input.writeByte(0);
                        Wire.writeVarInt(input, 0);
                        input.writeByte(random.nextInt(256));
                    }
                    case 1 -> {
                        input.writeByte(6);
                        Wire.writeVarInt(input, 20);
                        Wire.writeVarInt(input, EntityPose.CROUCHING.ordinal());
                    }
                    case 2 -> {
                        input.writeByte(9);
                        Wire.writeVarInt(input, 3);
                        input.writeFloat(random.nextFloat() * 20);
                    }
                    case 3 -> {
                        input.writeByte(1);
                        Wire.writeVarInt(input, 1);
                        Wire.writeVarInt(input, 300);
                    }
                    default -> {
                        input.writeByte(10 + i);
                        Wire.writeVarInt(input, 8);
                        input.writeBoolean(random.nextBoolean());
                    }
                }
            }
            input.writeByte(255);
            packets[p] = input;
        }
        return packets;
    }

    /** The base revision's per-packet decode: a list of decoded values, then one entry object each. */
    private static EntityMetadata legacyOnce(WireValueDecoder.Registries registries, ByteBuf input) {
        var decoder = ProtocolCodecs.decoder();
        int id = Wire.readVarInt(input);
        int payloadStart = input.readerIndex();
        var decoded = decoder.metadata(VERSION, input, registries, ModelNbtFactory.INSTANCE);
        int payloadLength = decoded.isEmpty()
                ? 0
                : decoded.getLast().offset() + decoded.getLast().length() - payloadStart;
        byte[] payload = ByteBufUtil.getBytes(input, payloadStart, payloadLength);
        var entries = new EntityMetadata.Entry[decoded.size()];
        for (int i = 0; i < entries.length; i++) {
            var value = decoded.get(i);
            entries[i] = EntityMetadata.Entry.of(
                    value.index(),
                    convert(value.kind(), value.value()),
                    payload,
                    value.offset() - payloadStart,
                    value.length());
        }
        return new EntityMetadata(id, List.of(entries));
    }

    private static Object convert(String kind, Object value) {
        return switch (kind) {
            case "pose" -> EntityPose.byId((int) value);
            default -> value;
        };
    }

    /** The base revision's held reader: values decoded into held arrays, then one entry object each. */
    private static final class LegacyHeld implements WireValueDecoder.MetadataSink {
        private final WireValueDecoder.Registries registries;
        private int[] indices = new int[4], offsets = new int[4], lengths = new int[4];
        private String[] kinds = new String[4];
        private Object[] values = new Object[4];
        private int size;

        LegacyHeld(WireValueDecoder.Registries registries) {
            this.registries = registries;
        }

        @Override
        public void accept(int index, String kind, Object value, int offset, int length) {
            if (size == kinds.length) {
                int capacity = size * 2;
                indices = Arrays.copyOf(indices, capacity);
                kinds = Arrays.copyOf(kinds, capacity);
                values = Arrays.copyOf(values, capacity);
                offsets = Arrays.copyOf(offsets, capacity);
                lengths = Arrays.copyOf(lengths, capacity);
            }
            indices[size] = index;
            kinds[size] = kind;
            values[size] = value;
            offsets[size] = offset;
            lengths[size] = length;
            size++;
        }

        EntityMetadata read(ByteBuf input) {
            int id = Wire.readVarInt(input);
            int payloadStart = input.readerIndex();
            try {
                ProtocolCodecs.decoder().metadata(VERSION, input, registries, ModelNbtFactory.INSTANCE, this);
                int payloadLength = size == 0 ? 0 : offsets[size - 1] + lengths[size - 1] - payloadStart;
                byte[] payload = ByteBufUtil.getBytes(input, payloadStart, payloadLength);
                var entries = new EntityMetadata.Entry[size];
                for (int i = 0; i < size; i++) {
                    entries[i] = EntityMetadata.Entry.of(
                            indices[i], convert(kinds[i], values[i]), payload, offsets[i] - payloadStart, lengths[i]);
                }
                if (size == 1) return new EntityMetadata(id, List.of(entries[0]));
                if (size == 2) return new EntityMetadata(id, List.of(entries[0], entries[1]));
                return new EntityMetadata(id, List.of(entries));
            } finally {
                Arrays.fill(values, 0, size, null);
                size = 0;
            }
        }
    }
}
