package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.wire.Wire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and allocation per decoded entity metadata packet, over a mix of one, two and four entry packets;
 * CULT_METADATA_BENCH=1 enables it.
 */
@EnabledIfEnvironmentVariable(named = "CULT_METADATA_BENCH", matches = "1")
class ModelEntityMetadataBenchTest {
    private static final int REPS = 9;
    private static final int PACKETS = 5_000_000;
    static int sink;

    @Test
    void decode() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var values = new ModelEntityMetadataValues(
                ProtocolCodecs.decoder(),
                ProtocolVersion.V26_3,
                new WireRegistryState(ProtocolVersion.V26_3, () -> null),
                name -> null);
        ByteBuf[] packets = new ByteBuf[10];
        for (int i = 0; i < packets.length; i++) packets[i] = packet(i < 5 ? 1 : i < 8 ? 2 : 4);
        for (int i = 0; i < 3; i++) run(bean, values, packets);
        long[] cpu = new long[REPS], bytes = new long[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long[] result = run(bean, values, packets);
            cpu[rep] = result[0];
            bytes[rep] = result[1];
        }
        Arrays.sort(cpu);
        Arrays.sort(bytes);
        System.out.printf(
                "METADATA-DECODE cpu median %.1f ns/packet (min %.1f max %.1f) alloc median %.1f B/packet (min %.1f"
                        + " max %.1f)%n",
                cpu[REPS / 2] / (double) PACKETS,
                cpu[0] / (double) PACKETS,
                cpu[REPS - 1] / (double) PACKETS,
                bytes[REPS / 2] / (double) PACKETS,
                bytes[0] / (double) PACKETS,
                bytes[REPS - 1] / (double) PACKETS);
        for (ByteBuf packet : packets) packet.release();
    }

    private static long[] run(ThreadMXBean bean, ModelEntityMetadataValues values, ByteBuf[] packets) {
        long thread = Thread.currentThread().getId();
        long allocated = bean.getThreadAllocatedBytes(thread);
        long cpu = bean.getCurrentThreadCpuTime();
        int total = 0;
        for (int i = 0; i < PACKETS; i++) {
            ByteBuf packet = packets[i % packets.length];
            total += values.read(packet.readerIndex(0)).packedItems().size();
        }
        long spent = bean.getCurrentThreadCpuTime() - cpu;
        long garbage = bean.getThreadAllocatedBytes(thread) - allocated;
        sink += total;
        return new long[] {spent, garbage};
    }

    /** Shared flags, then pose, health and an integer: serializers 0, 20, 3 and 1 on 26.3. */
    private static ByteBuf packet(int entries) {
        ByteBuf input = Unpooled.buffer();
        Wire.writeVarInt(input, 42);
        input.writeByte(0);
        Wire.writeVarInt(input, 0);
        input.writeByte(2);
        if (entries > 1) {
            input.writeByte(6);
            Wire.writeVarInt(input, 20);
            Wire.writeVarInt(input, EntityPose.CROUCHING.ordinal());
        }
        if (entries > 2) {
            input.writeByte(9);
            Wire.writeVarInt(input, 3);
            input.writeFloat(1.5F);
            input.writeByte(10);
            Wire.writeVarInt(input, 1);
            Wire.writeVarInt(input, 300);
        }
        input.writeByte(255);
        return input;
    }
}
