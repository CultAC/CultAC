package ac.cult.cultac.protocol;

import static ac.cult.cultac.protocol.ConnectionPhase.PLAY;
import static ac.cult.cultac.protocol.PacketDirection.CLIENTBOUND;

import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Packets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Heap allocation and thread CPU of translated reads and writes in {@link ProjectedPackets}, around an adapter that
 * allocates nothing. The base path hands every call a state other than the previous call's, so each builds its
 * context as an unshared one would. CULT_PROJECTED_BENCH=1 enables it.
 */
@EnabledIfEnvironmentVariable(named = "CULT_PROJECTED_BENCH", matches = "1")
class ProjectedPacketsBenchTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int REPS = 9;
    private static final int OPS = 2_000_000;

    record WrittenValue(int id) implements ClientboundPacket {}

    private static final PacketType<WrittenValue> WRITTEN = new PacketType<>(
            "clientbound.block_event",
            WrittenValue.class,
            CLIENTBOUND,
            Set.of(PLAY),
            List.of("minecraft:block_event"),
            ProtocolVersion.V26_3,
            new WritablePacketCodec<>() {
                public boolean requiresModelValues() {
                    return true;
                }

                public WrittenValue read(ByteBuf input, ProtocolContext context) {
                    throw new UnsupportedOperationException();
                }

                public void write(ByteBuf output, ProtocolContext context, WrittenValue value) {
                    throw new UnsupportedOperationException();
                }
            });

    static long sink;

    interface Op {
        void run(int i);
    }

    @Test
    void translatedContexts() throws Exception {
        var model = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3), new ArrayList<>(Packets.all()));
        var wire = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V1_21_11));
        var origin = new Vec3d(0, 0, 0);
        var addEntity = new ClientboundAddEntity(1, "minecraft:pig", origin, 0, 0, 0, new UUID(0, 0), origin);
        var blockEvent = new ClientboundBlockEvent(new BlockPos(0, 0, 0), 0, 0, 0);
        var adapter = new PacketValueAdapter() {
            public Object read(ByteBuf input, ProtocolContext context) {
                input.skipBytes(input.readableBytes());
                return context.type() == ClientboundPackets.ADD_ENTITY ? addEntity : blockEvent;
            }

            public void write(ByteBuf output, ProtocolContext context, Object packet) {
                output.writeByte(context.variant());
            }
        };
        CodecState shared = state(), other = state();
        ByteBufAllocator allocator = new PooledByteBufAllocator(false);
        ByteBuf[] frames = {
            frame(wire, "minecraft:add_entity"),
            frame(wire, "minecraft:block_event"),
            frame(wire, "minecraft:add_entity")
        };
        var value = new WrittenValue(1);
        try (var packets = new ProjectedPackets(wire, model, () -> adapter)) {
            Op readBase = i ->
                    sink += packets.readOne(PLAY, CLIENTBOUND, frames[i % 3], (i & 1) == 0 ? shared : other, t -> true)
                            .hashCode();
            Op readBranch = i -> sink += packets.readOne(PLAY, CLIENTBOUND, frames[i % 3], shared, t -> true)
                    .hashCode();
            Op writeBase = i -> release(packets.encode(PLAY, WRITTEN, value, allocator, (i & 1) == 0 ? shared : other));
            Op writeBranch = i -> release(packets.encode(PLAY, WRITTEN, value, allocator, shared));
            compare("translated read", readBase, readBranch);
            compare("translated write", writeBase, writeBranch);
        } finally {
            for (ByteBuf frame : frames) frame.release();
        }
    }

    private static void compare(String label, Op base, Op branch) throws Exception {
        for (int warm = 0; warm < 5; warm++) {
            run(base, OPS);
            run(branch, OPS);
        }
        double[][] bytes = new double[2][REPS], cpu = new double[2][REPS];
        for (int rep = 0; rep < REPS; rep++) {
            for (int k = 0; k < 2; k++) {
                int which = (rep + k) % 2;
                Op op = which == 0 ? base : branch;
                long allocated = THREADS.getCurrentThreadAllocatedBytes();
                long start = THREADS.getCurrentThreadCpuTime();
                run(op, OPS);
                cpu[which][rep] = (THREADS.getCurrentThreadCpuTime() - start) / (double) OPS;
                bytes[which][rep] = (THREADS.getCurrentThreadAllocatedBytes() - allocated) / (double) OPS;
            }
        }
        report(label + " base bytes/op", bytes[0]);
        report(label + " branch bytes/op", bytes[1]);
        report(label + " base cpu ns/op", cpu[0]);
        report(label + " branch cpu ns/op", cpu[1]);
    }

    private static void run(Op op, int count) {
        for (int i = 0; i < count; i++) op.run(i);
    }

    private static void release(ProjectedPackets.Encoded encoded) {
        for (int i = 0; i < encoded.frames().size(); i++) {
            sink += encoded.frames().get(i).readableBytes();
            encoded.frames().get(i).release();
        }
    }

    private static ByteBuf frame(ProtocolRuntime wire, String name) {
        ByteBuf frame = Unpooled.directBuffer();
        Wire.writeVarInt(frame, wire.data().packets(PLAY, CLIENTBOUND).id(name));
        frame.writeZero(24);
        return frame;
    }

    private static CodecState state() {
        return new CodecState() {
            public <T> T require(Class<T> type) {
                return CodecState.EMPTY.require(type);
            }
        };
    }

    private static void report(String label, double[] samples) throws Exception {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        String line = String.format(
                        Locale.ROOT,
                        "%s: median %.2f, min %.2f, max %.2f",
                        label,
                        sorted[sorted.length / 2],
                        sorted[0],
                        sorted[sorted.length - 1])
                + System.lineSeparator();
        System.out.print(line);
        String out = System.getenv("CULT_BENCH_OUT");
        if (out != null)
            Files.writeString(
                    Path.of(out), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
