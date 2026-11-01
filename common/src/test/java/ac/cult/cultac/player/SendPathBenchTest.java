package ac.cult.cultac.player;

import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.CodecState;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProjectedPackets;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.PalettedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Thread CPU of the per-movement proof-ping path and of chunk section counting; CULT_SEND_BENCH=1 enables it. */
@EnabledIfEnvironmentVariable(named = "CULT_SEND_BENCH", matches = "1")
class SendPathBenchTest {
    private static final int REPS = 9;
    private static final int IN_FLIGHT =
            Integer.parseInt(System.getenv().getOrDefault("CULT_SEND_BENCH_IN_FLIGHT", "16"));
    static long sink;

    interface Op {
        void run() throws Throwable;
    }

    @Test
    void proofPingPath() throws Throwable {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        player.user.bind(mock(PlatformPlayer.class));
        try {
            var listener = new PacketPingListener();
            var outstanding = new ArrayDeque<Integer>();
            Op ping = () -> {
                var transaction = player.createTrackedTransactionPacketForDeferredSend();
                var packet = (ClientboundPing) transaction.packet().packet();
                listener.onPing(
                        new PacketSendEvent<>(
                                player.user, ConnectionPhase.PLAY, ClientboundPackets.PING, packet, false),
                        player,
                        packet);
                player.markTrackedTransactionPacketSent(transaction);
                outstanding.add(transaction.id());
                if (outstanding.size() > IN_FLIGHT) {
                    var pong = new ServerboundPong(outstanding.poll());
                    listener.onPong(
                            new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.PONG, pong),
                            player,
                            pong);
                }
            };
            measure("player-proof-ping in-flight=" + IN_FLIGHT, ping, 3_000_000);
        } finally {
            OfflinePlayers.close(player);
        }

        var runtime = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
        ByteBufAllocator allocator = new PooledByteBufAllocator(false);
        try (var packets = new ProjectedPackets(runtime, runtime, () -> null)) {
            Delimiter delimiter = delimiterEncoder(packets, allocator);
            var pingPacket = new ClientboundPing(Short.MIN_VALUE - 7);
            Op encode = () -> {
                var opening = delimiter.encode();
                var proof = packets.encode(
                        ConnectionPhase.PLAY, ClientboundPackets.PING, pingPacket, allocator, CodecState.EMPTY);
                var closing = delimiter.encode();
                sink += release(opening) + release(proof) + release(closing);
            };
            measure("encode-delimiter-ping-delimiter", encode, 2_000_000);
        }

        var random = new SplittableRandom(0xC0DEL);
        var sections = new PalettedSection[64];
        for (int i = 0; i < sections.length; i++) sections[i] = section(random, 4 + i % 5);
        int[] next = {0};
        Op count = () -> {
            var cached = new CompensatedWorld.CachedSection(sections[next[0]++ & 63]);
            sink += cached.isShared() ? 1 : 0;
        };
        measure("cached-section-counts", count, 60_000);
    }

    interface Delimiter {
        ProjectedPackets.Encoded encode() throws Throwable;
    }

    /** The branch's cached delimiter encode when it exists, else the generic encode base uses. */
    private static Delimiter delimiterEncoder(ProjectedPackets packets, ByteBufAllocator allocator)
            throws ReflectiveOperationException {
        try {
            var method = ProjectedPackets.class.getMethod(
                    "encodeBundleDelimiter", ConnectionPhase.class, ByteBufAllocator.class, CodecState.class);
            MethodHandle handle = MethodHandles.publicLookup().unreflect(method);
            System.out.println("delimiter: encodeBundleDelimiter");
            return () -> new ProjectedPackets.Encoded(
                    List.of((ByteBuf) handle.invokeExact(packets, ConnectionPhase.PLAY, allocator, CodecState.EMPTY)));
        } catch (NoSuchMethodException absent) {
            var type = ClientboundPackets.BUNDLE_DELIMITER;
            System.out.println("delimiter: encode");
            return () -> packets.encode(ConnectionPhase.PLAY, type, type.opaqueValue(), allocator, CodecState.EMPTY);
        }
    }

    private static int release(ProjectedPackets.Encoded encoded) {
        int bytes = 0;
        for (ByteBuf frame : encoded.frames()) {
            bytes += frame.readableBytes();
            frame.release();
        }
        return bytes;
    }

    private static PalettedSection section(SplittableRandom random, int bits) {
        var input = Unpooled.buffer();
        try {
            int paletteSize = 1 + random.nextInt((1 << bits) - 1);
            input.writeByte(bits);
            Wire.writeVarInt(input, paletteSize);
            for (int i = 0; i < paletteSize; i++) Wire.writeVarInt(input, random.nextInt(20_000));
            int perWord = 64 / bits;
            for (int word = 0; word < (4096 + perWord - 1) / perWord; word++) {
                long value = 0;
                for (int j = 0; j < perWord; j++) value |= (long) random.nextInt(paletteSize) << (j * bits);
                input.writeLong(value);
            }
            return PalettedSection.readBlocks(input);
        } finally {
            input.release();
        }
    }

    private static void measure(String name, Op op, int ops) throws Throwable {
        for (int warm = 0; warm < 3; warm++) for (int i = 0; i < ops; i++) op.run();
        var bean = ManagementFactory.getThreadMXBean();
        long[] perOp = new long[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long start = bean.getCurrentThreadCpuTime();
            for (int i = 0; i < ops; i++) op.run();
            perOp[rep] = (bean.getCurrentThreadCpuTime() - start) / ops;
        }
        long[] sorted = perOp.clone();
        Arrays.sort(sorted);
        String line = name + " ns/op median=" + sorted[REPS / 2] + " min=" + sorted[0] + " max=" + sorted[REPS - 1]
                + " all=" + Arrays.toString(perOp) + System.lineSeparator();
        System.out.print(line);
        String out = System.getenv("CULT_BENCH_OUT");
        if (out != null)
            Files.writeString(
                    Path.of(out), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
