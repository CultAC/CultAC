package ac.cult.cultac.protocol.codec.entity;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.protocol.CodecState;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.wire.Wire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Bytes and thread CPU per linear or stepped move read as a record and read into a sink, interleaved; runs only with
 * CULT_MOVE_SINK_BENCH set.
 */
class MoveEntitySinkBenchTest {
    private static final int PACKETS = 1024;
    private static final int REPS = 15;
    private static final long REP_CPU_NANOS = 200_000_000L;

    private static final class Sink implements CodecState, MoveEntityCodec.Sink {
        long sum;

        @Override
        public <T> T require(Class<T> type) {
            return CodecState.EMPTY.require(type);
        }

        @Override
        public boolean sinksMove() {
            return true;
        }

        @Override
        public void move(
                int entityId,
                short x,
                short y,
                short z,
                float yaw,
                float pitch,
                boolean onGround,
                boolean hasPosition,
                boolean hasRotation) {
            sum += entityId + x + y + z + (long) yaw + (onGround ? 1 : 0);
        }

        @Override
        public boolean sinksSteps(int steps) {
            return steps <= 16;
        }

        @Override
        public void step(int index, short x, short y, short z, int ticks) {
            sum += index + x + y + z + ticks;
        }

        @Override
        public void steppedMove(
                int entityId, int steps, float yaw, float pitch, boolean onGround, boolean hasRotation) {
            sum += entityId + steps + (long) yaw + (onGround ? 1 : 0);
        }
    }

    @Test
    void steppedBench() {
        assumeTrue(System.getenv("CULT_MOVE_SINK_BENCH") != null);
        ProtocolData data = ProtocolData.load(ProtocolVersion.V26_3);
        ProtocolContext plain = EntityListCodecEquivalenceTest.context(ClientboundPackets.MOVE_ENTITY, data, 1);
        var sink = new Sink();
        ProtocolContext sinking = plain.withState(sink);
        for (String mix : new String[] {"1 step", "mix"}) {
            ByteBuf[] packets = new ByteBuf[PACKETS];
            Random random = new Random(67);
            for (int i = 0; i < PACKETS; i++) {
                ByteBuf bytes = Unpooled.directBuffer();
                Wire.writeVarInt(bytes, random.nextInt(1 << 16));
                int steps = mix.equals("mix") && random.nextInt(10) < 3 ? 2 + random.nextInt(2) : 1;
                Wire.writeVarInt(bytes, steps << 1 | random.nextInt(2));
                for (int s = 0; s < steps; s++) {
                    Wire.writeVarInt(bytes, 1 + random.nextInt(3));
                    for (int j = 0; j < 3; j++) bytes.writeShort(random.nextInt(65536));
                }
                bytes.writeByte(random.nextInt(256)).writeByte(random.nextInt(256));
                packets[i] = bytes;
            }
            measure("V26_3 stepped " + mix, packets, plain, sinking, sink);
            for (ByteBuf packet : packets) packet.release();
        }
    }

    private static void measure(
            String label, ByteBuf[] packets, ProtocolContext plain, ProtocolContext sinking, Sink sink) {
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        var codec = new MoveEntityCodec();
        long[] sum = {0};
        Object[] held = new Object[1];
        Runnable record = () -> {
            for (ByteBuf packet : packets) held[0] = codec.read(packet.readerIndex(0), plain);
            sum[0] += held[0].hashCode();
        };
        Runnable sunk = () -> {
            for (ByteBuf packet : packets) held[0] = codec.read(packet.readerIndex(0), sinking);
            sum[0] += held[0].hashCode();
        };
        Runnable[] runs = {record, sunk};
        for (int i = 0; i < 20_000; i++) for (Runnable run : runs) run.run();
        double[][] bytes = new double[2][REPS], nanos = new double[2][REPS];
        long thread = Thread.currentThread().threadId();
        for (int rep = 0; rep < REPS; rep++) {
            for (int k = 0; k < runs.length; k++) {
                int r = (rep & 1) == 0 ? k : 1 - k;
                long beginBytes = bean.getThreadAllocatedBytes(thread);
                long begin = bean.getCurrentThreadCpuTime();
                long reads = 0, elapsed;
                do {
                    runs[r].run();
                    reads += packets.length;
                    elapsed = bean.getCurrentThreadCpuTime() - begin;
                } while (elapsed < REP_CPU_NANOS);
                bytes[r][rep] = (double) (bean.getThreadAllocatedBytes(thread) - beginBytes) / reads;
                nanos[r][rep] = (double) elapsed / reads;
            }
        }
        String[] names = {"record", "sunk"};
        for (int r = 0; r < runs.length; r++) {
            Arrays.sort(bytes[r]);
            Arrays.sort(nanos[r]);
            System.out.printf(
                    "MOVE-SINK-BENCH %s %s: %.1f B/read [%.1f-%.1f], %.1f ns/read [%.1f-%.1f]%n",
                    label,
                    names[r],
                    bytes[r][REPS / 2],
                    bytes[r][0],
                    bytes[r][REPS - 1],
                    nanos[r][REPS / 2],
                    nanos[r][0],
                    nanos[r][REPS - 1]);
        }
        System.out.println("MOVE-SINK-BENCH checksum " + (sum[0] + sink.sum));
    }

    @Test
    void bench() {
        assumeTrue(System.getenv("CULT_MOVE_SINK_BENCH") != null);
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (ProtocolVersion version : new ProtocolVersion[] {ProtocolVersion.V1_21_11, ProtocolVersion.V26_3}) {
            ProtocolData data = ProtocolData.load(version);
            ProtocolContext plain = EntityListCodecEquivalenceTest.context(ClientboundPackets.MOVE_ENTITY, data, 1);
            var sink = new Sink();
            ProtocolContext sinking = plain.withState(sink);
            ByteBuf[] packets = new ByteBuf[PACKETS];
            Random random = new Random(61);
            for (int i = 0; i < PACKETS; i++) {
                ByteBuf bytes = Unpooled.directBuffer();
                Wire.writeVarInt(bytes, random.nextInt(1 << 16));
                if (version.atLeast(ProtocolVersion.V26_3)) Wire.writeVarInt(bytes, random.nextInt(2));
                for (int j = 0; j < 3; j++) bytes.writeShort(random.nextInt(65536));
                bytes.writeByte(random.nextInt(256)).writeByte(random.nextInt(256));
                if (!version.atLeast(ProtocolVersion.V26_3)) bytes.writeBoolean(random.nextBoolean());
                packets[i] = bytes;
            }
            var codec = new MoveEntityCodec();
            long[] sum = {0};
            // The dispatched packet escapes into its event, so the bench keeps each read where C2 cannot drop it.
            Object[] held = new Object[1];
            Runnable record = () -> {
                for (ByteBuf packet : packets) held[0] = codec.read(packet.readerIndex(0), plain);
                sum[0] += held[0].hashCode();
            };
            Runnable sunk = () -> {
                for (ByteBuf packet : packets) held[0] = codec.read(packet.readerIndex(0), sinking);
                sum[0] += held[0].hashCode();
            };
            Runnable[] runs = {record, sunk};
            for (int i = 0; i < 20_000; i++) for (Runnable run : runs) run.run();
            double[][] bytes = new double[2][REPS], nanos = new double[2][REPS];
            long thread = Thread.currentThread().threadId();
            for (int rep = 0; rep < REPS; rep++) {
                for (int r = 0; r < runs.length; r++) {
                    long beginBytes = bean.getThreadAllocatedBytes(thread);
                    long begin = bean.getCurrentThreadCpuTime();
                    long reads = 0, elapsed;
                    do {
                        runs[r].run();
                        reads += PACKETS;
                        elapsed = bean.getCurrentThreadCpuTime() - begin;
                    } while (elapsed < REP_CPU_NANOS);
                    bytes[r][rep] = (double) (bean.getThreadAllocatedBytes(thread) - beginBytes) / reads;
                    nanos[r][rep] = (double) elapsed / reads;
                }
            }
            String[] names = {"record", "sunk"};
            for (int r = 0; r < runs.length; r++) {
                Arrays.sort(bytes[r]);
                Arrays.sort(nanos[r]);
                System.out.printf(
                        "MOVE-SINK-BENCH %s %s: %.1f B/read [%.1f-%.1f], %.1f ns/read [%.1f-%.1f]%n",
                        version,
                        names[r],
                        bytes[r][REPS / 2],
                        bytes[r][0],
                        bytes[r][REPS - 1],
                        nanos[r][REPS / 2],
                        nanos[r][0],
                        nanos[r][REPS - 1]);
            }
            System.out.println("MOVE-SINK-BENCH checksum " + (sum[0] + sink.sum));
            for (ByteBuf packet : packets) packet.release();
        }
    }
}
