package ac.cult.cultac.protocol.codec.entity;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Bytes and thread CPU per read of the move, position sync and attribute codecs against their list-building form,
 * interleaved; runs only with CULT_ENTITY_CODEC_BENCH set to an output file.
 */
class EntityListCodecBenchTest {
    private static final int PACKETS = 512;
    private static final int WARMUP = 400;
    private static final int REPS = 15;
    private static final long REP_NANOS = 150_000_000L;

    private record Workload(String name, ByteBuf[] packets, ProtocolContext context) {}

    @Test
    void bench() throws Exception {
        String out = System.getenv("CULT_ENTITY_CODEC_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        Random random = new Random(5);
        ProtocolData modern = ProtocolData.load(ProtocolVersion.V26_3);
        Workload[] workloads = {
            new Workload(
                    "move 26.3, 1 step",
                    move(random, 1),
                    EntityListCodecEquivalenceTest.context(ClientboundPackets.MOVE_ENTITY, modern, 1)),
            new Workload(
                    "move 26.3, 3 steps",
                    move(random, 3),
                    EntityListCodecEquivalenceTest.context(ClientboundPackets.MOVE_ENTITY, modern, 1)),
            new Workload(
                    "position sync 26.3, 2 steps",
                    sync(random),
                    EntityListCodecEquivalenceTest.context(ClientboundPackets.ENTITY_POSITION_SYNC, modern, 0)),
            new Workload(
                    "attributes 26.3, 2 without modifiers",
                    attributes(random, modern),
                    EntityListCodecEquivalenceTest.context(ClientboundPackets.UPDATE_ATTRIBUTES, modern, 0)),
        };
        BiFunction<ByteBuf, ProtocolContext, Object>[][] readers = readers();
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        var report = new StringBuilder(
                "workload | base B/read | new B/read | base ns/read | new ns/read (median [min-max])\n");
        for (int w = 0; w < workloads.length; w++) {
            Workload workload = workloads[w];
            for (int i = 0; i < WARMUP; i++) for (int r = 0; r < 2; r++) run(workload, readers[w][r]);
            double[][] bytes = new double[2][REPS];
            double[][] nanos = new double[2][REPS];
            for (int rep = 0; rep < REPS; rep++) {
                for (int r = (rep & 1); r < (rep & 1) + 2; r++) {
                    int which = r & 1;
                    long reads = 0;
                    long beginBytes = bean.getThreadAllocatedBytes(thread);
                    long begin = bean.getCurrentThreadCpuTime();
                    long elapsed;
                    do {
                        run(workload, readers[w][which]);
                        reads += PACKETS;
                        elapsed = bean.getCurrentThreadCpuTime() - begin;
                    } while (elapsed < REP_NANOS);
                    bytes[which][rep] = (double) (bean.getThreadAllocatedBytes(thread) - beginBytes) / reads;
                    nanos[which][rep] = (double) elapsed / reads;
                }
            }
            report.append(workload.name())
                    .append(" | ")
                    .append(stat(bytes[0]))
                    .append(" | ")
                    .append(stat(bytes[1]))
                    .append(" | ")
                    .append(stat(nanos[0]))
                    .append(" | ")
                    .append(stat(nanos[1]))
                    .append('\n');
        }
        Files.writeString(
                Path.of(out), report, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    @SuppressWarnings("unchecked")
    private static BiFunction<ByteBuf, ProtocolContext, Object>[][] readers() {
        var move = new MoveEntityCodec();
        var sync = new EntityPositionSyncCodec();
        var attributes = new UpdateAttributesCodec();
        BiFunction<ByteBuf, ProtocolContext, Object> baseMove = EntityListCodecEquivalenceTest::baseMove;
        BiFunction<ByteBuf, ProtocolContext, Object> newMove = (input, context) -> move.read(input, context);
        BiFunction<ByteBuf, ProtocolContext, Object> baseSync = EntityListCodecEquivalenceTest::basePositionSync;
        BiFunction<ByteBuf, ProtocolContext, Object> newSync = (input, context) -> sync.read(input, context);
        BiFunction<ByteBuf, ProtocolContext, Object> baseAttributes = EntityListCodecEquivalenceTest::baseAttributes;
        BiFunction<ByteBuf, ProtocolContext, Object> newAttributes =
                (input, context) -> attributes.read(input, context);
        return new BiFunction[][] {
            {baseMove, newMove}, {baseMove, newMove}, {baseSync, newSync}, {baseAttributes, newAttributes},
        };
    }

    private static Object sink;

    private static void run(Workload workload, BiFunction<ByteBuf, ProtocolContext, Object> reader) {
        for (ByteBuf packet : workload.packets()) {
            packet.readerIndex(0);
            sink = reader.apply(packet, workload.context());
        }
    }

    private static String stat(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.format("%.1f [%.1f-%.1f]", sorted[sorted.length / 2], sorted[0], sorted[sorted.length - 1]);
    }

    private static ByteBuf[] move(Random random, int steps) {
        var packets = new ByteBuf[PACKETS];
        for (int n = 0; n < PACKETS; n++) {
            ByteBuf bytes = Unpooled.buffer();
            Wire.writeVarInt(bytes, 1000 + random.nextInt(1000));
            Wire.writeVarInt(bytes, steps << 1 | 1);
            for (int i = 0; i < steps; i++) {
                Wire.writeVarInt(bytes, 1);
                for (int axis = 0; axis < 3; axis++) bytes.writeShort(random.nextInt(2048) - 1024);
            }
            bytes.writeByte(random.nextInt(256)).writeByte(random.nextInt(256));
            packets[n] = bytes;
        }
        return packets;
    }

    private static ByteBuf[] sync(Random random) {
        var packets = new ByteBuf[PACKETS];
        for (int n = 0; n < PACKETS; n++) {
            ByteBuf bytes = Unpooled.buffer();
            Wire.writeVarInt(bytes, 1000 + random.nextInt(1000));
            Wire.writeVarInt(bytes, 1);
            Wire.writeVarInt(bytes, 2);
            for (int i = 0; i < 2; i++) {
                Wire.writeVec3(bytes, new Vec3d(random.nextDouble() * 32, 64, random.nextDouble() * 32));
                Wire.writeVarInt(bytes, 1);
            }
            bytes.writeFloat(random.nextFloat()).writeFloat(random.nextFloat()).writeBoolean(true);
            packets[n] = bytes;
        }
        return packets;
    }

    private static ByteBuf[] attributes(Random random, ProtocolData data) {
        int ids = data.registry("minecraft:attribute").size();
        var packets = new ByteBuf[PACKETS];
        for (int n = 0; n < PACKETS; n++) {
            ByteBuf bytes = Unpooled.buffer();
            Wire.writeVarInt(bytes, 1000 + random.nextInt(1000));
            Wire.writeVarInt(bytes, 2);
            for (int i = 0; i < 2; i++) {
                Wire.writeVarInt(bytes, random.nextInt(ids));
                bytes.writeDouble(random.nextDouble());
                Wire.writeVarInt(bytes, 0);
            }
            packets[n] = bytes;
        }
        return packets;
    }
}
