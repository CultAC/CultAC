package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundKeepAlive;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.wire.Wire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Heap allocation and thread CPU per frame of the transport's own work: routed frames through {@link CultEncoder}
 * and {@link CultDecoder} with trivial scanned handlers, and outbound packets whose listener adds a write or a task
 * after them. Runs only with {@code CULT_TRANSPORT_ALLOC_BENCH} set; results go to stdout and
 * {@code build/transport-alloc-bench.txt}.
 */
class TransportAllocBenchTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static PrintStream report;

    @Test
    void transportAllocation() throws IOException {
        Assumptions.assumeTrue(System.getenv("CULT_TRANSPORT_ALLOC_BENCH") != null);
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("CULT_TRANSPORT_ALLOC_BENCH_REPS", "9"));
        Path out = Path.of("build", "transport-alloc-bench.txt");
        Files.createDirectories(out.getParent());
        report = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8);
        OfflineCultTestBootstrap.installConfig();
        var dispatcher = new PacketDispatcher(
                CultAPI.INSTANCE.getNetworkManager().dispatcher().runtime());
        dispatcher.registerOwn(registrar -> {
            registrar.registerListener(PacketListenerPriority.NORMAL, new DispatchCpuBenchTest.TrivialA());
            registrar.registerListener(PacketListenerPriority.NORMAL, new DispatchCpuBenchTest.TrivialB());
            registrar.registerListener(PacketListenerPriority.NORMAL, new Proofs());
        });
        var channel = new EmbeddedChannel();
        var connection = new CultConnection(channel, dispatcher, ignored -> new PacketOwner(channel.eventLoop(), null));
        for (var direction : PacketDirection.values()) connection.phase(direction, ConnectionPhase.PLAY);
        var user = new User(new User.Profile(UUID.randomUUID(), ".Alloc_Bench"), connection);
        CultPlayer player = new CultPlayer(user);
        synchronized (connection) {
            connection.player(player);
        }
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        CultDecoder.install(connection);
        CultEncoder.install(connection);
        var data = dispatcher.runtime().data();
        int pongId =
                data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND).id("minecraft:pong");
        int pingId =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:ping");
        int keepAliveId =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:keep_alive");
        ByteBuf pong = Unpooled.buffer();
        Wire.writeVarInt(pong, pongId);
        pong.writeInt(1);
        ByteBuf ping = Unpooled.buffer();
        Wire.writeVarInt(ping, pingId);
        ping.writeInt(1);
        ByteBuf keepAliveWrite = Unpooled.buffer();
        Wire.writeVarInt(keepAliveWrite, keepAliveId);
        keepAliveWrite.writeLong(2);
        ByteBuf keepAliveTask = Unpooled.buffer();
        Wire.writeVarInt(keepAliveTask, keepAliveId);
        keepAliveTask.writeLong(3);
        Scenario[] scenarios = {
            new Scenario("inbound routed frame", () -> {
                channel.writeInbound(pong.retainedDuplicate());
                ReferenceCountUtil.release(channel.readInbound());
            }),
            new Scenario("outbound routed frame, flushed", () -> {
                channel.writeAndFlush(ping.retainedDuplicate());
                ReferenceCountUtil.release(channel.readOutbound());
            }),
            new Scenario("outbound routed frames, 4 per flush", () -> {
                for (int i = 0; i < 3; i++) channel.write(ping.retainedDuplicate());
                channel.writeAndFlush(ping.retainedDuplicate());
                for (Object written; (written = channel.readOutbound()) != null; ) ReferenceCountUtil.release(written);
            }),
            new Scenario("outbound frame with a write after it", () -> {
                channel.writeAndFlush(keepAliveWrite.retainedDuplicate());
                for (Object written; (written = channel.readOutbound()) != null; ) ReferenceCountUtil.release(written);
            }),
            new Scenario("outbound frame with a task after it", () -> {
                channel.writeAndFlush(keepAliveTask.retainedDuplicate());
                ReferenceCountUtil.release(channel.readOutbound());
            }),
        };
        int batch = 400_000;
        try {
            for (int warm = 0; warm < 3; warm++) {
                for (Scenario scenario : scenarios) scenario.run(batch);
            }
            for (int r = 0; r < repetitions; r++) {
                for (Scenario scenario : scenarios) scenario.measure(r, repetitions, batch);
            }
            for (Scenario scenario : scenarios) {
                log(scenario.label + " bytes/op", scenario.bytes);
                log(scenario.label + " cpu ns/op", scenario.cpu);
            }
            Assertions.assertEquals((long) (3 + repetitions) * batch, Proofs.tasks);
        } finally {
            pong.release();
            ping.release();
            keepAliveWrite.release();
            keepAliveTask.release();
            player.onRemove();
            channel.finishAndReleaseAll();
            report.close();
        }
    }

    private static final class Scenario {
        final String label;
        final Runnable op;
        double[] bytes;
        double[] cpu;

        Scenario(String label, Runnable op) {
            this.label = label;
            this.op = op;
        }

        void run(int count) {
            for (int i = 0; i < count; i++) op.run();
        }

        void measure(int round, int rounds, int count) {
            if (bytes == null) {
                bytes = new double[rounds];
                cpu = new double[rounds];
            }
            long allocated = THREADS.getCurrentThreadAllocatedBytes();
            long start = THREADS.getCurrentThreadCpuTime();
            run(count);
            cpu[round] = (THREADS.getCurrentThreadCpuTime() - start) / (double) count;
            bytes[round] = (THREADS.getCurrentThreadAllocatedBytes() - allocated) / (double) count;
        }
    }

    /** Adds a write or a task after each keep-alive, as proof listeners do. */
    public static final class Proofs {
        static long tasks;

        @CultPacketHandler
        public void keepAlive(
                PacketSendEvent<ClientboundKeepAlive> event, CultPlayer player, ClientboundKeepAlive packet) {
            if (packet.id() == 2) event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(7), false));
            else event.getTasksAfterSend().add(() -> tasks++);
        }
    }

    private static void log(String label, double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        String line = String.format(
                Locale.ROOT,
                "%s: median %.2f, min %.2f, max %.2f, all %s",
                label,
                sorted[sorted.length / 2],
                sorted[0],
                sorted[sorted.length - 1],
                Arrays.toString(Arrays.stream(samples)
                        .map(v -> Math.round(v * 100) / 100.0)
                        .toArray()));
        System.out.println(line);
        report.println(line);
    }
}
