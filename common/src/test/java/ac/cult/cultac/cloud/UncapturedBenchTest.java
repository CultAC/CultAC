package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.cloud.diag.DiagUpload;
import ac.cult.cultac.cloud.diag.Diagnostics;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.wire.Wire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU, wall time and allocated bytes per tapped packet of one player's capture, through its two taps, its
 * tick-end verdict, the server's tick end, a check flag every {@value #FLAG_EVERY} ticks and a facts change a pass, on a
 * backend that is captured with {@code cloud.uncaptured-servers} empty, captured with the list naming another backend,
 * uncaptured, and with no capture installed at all. 22 packets a tick (20 entity moves, a movement, the tick end). Runs
 * only when {@code CULTAC_UNCAPTURED_BENCH} names a file the medians are appended to.
 */
class UncapturedBenchTest {
    private static final int TICKS = 4_000;
    private static final int PASSES = 25;
    private static final int WARMUP_REPS = 4;
    private static final int REPS = 9;
    private static final int CLIENTBOUND_PER_TICK = 20;
    private static final int PACKETS_PER_TICK = CLIENTBOUND_PER_TICK + 2;
    private static final int FLAG_EVERY = 20;

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Binds every segment at once to the backend the player is on, which it also names ahead. */
    private static final class Binder implements SegmentBinder {
        final Map<UUID, String> on = new ConcurrentHashMap<>();

        @Override
        public void started(CultPlayer player, Consumer<String> bind, boolean playing) {
            bind.accept(on.get(player.getUniqueId()));
        }

        @Override
        public String upcoming(CultPlayer player, boolean playing) {
            return on.get(player.getUniqueId());
        }
    }

    /** Sockets that connect at once, take every record and keep none, writing frames into one reused array. */
    private static final class Discard implements CloudCapture.Uplinks, CloudCapture.Uplink {
        final Diagnostics diagnostics = new Diagnostics(new DiagUpload(), "bench");
        final List<CloudConnection.Stream> streams = new ArrayList<>();
        byte[] scratch = new byte[1 << 16];
        long bytes;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public CloudCapture.Uplink open(String serverName) {
            return this;
        }

        @Override
        public void release(CloudCapture.Uplink uplink) {}

        @Override
        public String serverId() {
            return "bench";
        }

        @Override
        public boolean same(CloudCapture.Uplink other) {
            return other == this;
        }

        @Override
        public void register(UUID player, CloudConnection.Stream stream) {
            streams.add(stream);
            stream.connected(1);
        }

        @Override
        public void unregister(UUID player, CloudConnection.Stream stream) {}

        @Override
        public boolean send(long generation, byte[] record, long resolvedExtra) {
            bytes += record.length;
            return true;
        }

        @Override
        public byte[] frame(int length) {
            if (scratch.length < length) scratch = new byte[length];
            return scratch;
        }

        @Override
        public boolean send(long generation, byte[] record, int length, long resolvedExtra) {
            bytes += length;
            return true;
        }

        @Override
        public CloudConnection.Terms terms(long generation) {
            return CloudConnection.Terms.sections(0, 0);
        }

        @Override
        public int maxFrameBytes() {
            return 1 << 20;
        }

        @Override
        public Diagnostics diagnostics() {
            return diagnostics;
        }
    }

    /** Ends the inbound path: the message is released and goes no further. */
    private static final class InboundEnd extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            ReferenceCountUtil.release(message);
        }
    }

    /** Ends the outbound path: the message is released and the write completes. */
    private static final class OutboundEnd extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            ReferenceCountUtil.release(message);
        }
    }

    /** One player's pipeline, its packets fed straight into the taps' handlers. */
    private static final class Lane implements AutoCloseable {
        final String name;
        final CaptureFixture fixture;
        final ChannelHandlerContext inbound;
        final ChannelHandlerContext outbound;
        final ByteBuf[] entities = new ByteBuf[CLIENTBOUND_PER_TICK];
        final ByteBuf move;
        final ByteBuf tickEnd;
        final Check flagging;

        Lane(String name, Binder binder, Discard uplinks, String server, List<String> uncaptured, boolean tapped) {
            this.name = name;
            fixture = new CaptureFixture(CaptureFixture.Host.PROXY, false);
            CloudCapture.uplinks(uplinks);
            CloudCapture.segmentBinder(binder);
            CloudCapture.uncaptured(UncapturedServers.of(uncaptured));
            binder.on.put(fixture.player.getUniqueId(), server);
            var pipeline = fixture.channel.pipeline();
            pipeline.replace("via-decoder", "via-decoder", new InboundEnd());
            pipeline.replace("compression-encoder", "compression-encoder", new OutboundEnd());
            if (tapped) {
                fixture.phase(ConnectionPhase.CONFIGURATION);
                fixture.install();
                fixture.phase(ConnectionPhase.PLAY);
                fixture.capture.loginSent(new ClientboundLogin(
                        7, false, new PlayerSpawnInfo(0, "minecraft:overworld", GameMode.SURVIVAL)));
                fixture.channel.runPendingTasks();
                int[] all = new int[256];
                for (int i = 0; i < all.length; i++) all[i] = i;
                for (CloudConnection.Stream stream : uplinks.streams) {
                    stream.reply(1, new IntakeWire.Capture(fixture.player.getUniqueId(), all, all, new int[0]));
                }
                uplinks.streams.clear();
                fixture.channel.runPendingTasks();
                inbound = pipeline.context(CloudCapture.INBOUND);
                outbound = pipeline.context(CloudCapture.OUTBOUND);
            } else {
                inbound = pipeline.context("compression-decoder");
                outbound = pipeline.context("via-encoder");
            }
            SplittableRandom random = new SplittableRandom(name.hashCode());
            for (int i = 0; i < entities.length; i++) {
                entities[i] = packet(fixture.id(PacketDirection.CLIENTBOUND, "move_entity_pos"), 12, random);
            }
            move = packet(fixture.id(PacketDirection.SERVERBOUND, "move_player_pos"), 26, random);
            tickEnd = packet(fixture.id(PacketDirection.SERVERBOUND, "client_tick_end"), 0, random);
            flagging = fixture.player.checkManager.getCheck(PlayerReach.class);
        }

        private static ByteBuf packet(int id, int body, SplittableRandom random) {
            ByteBuf bytes = Unpooled.directBuffer(5 + body);
            Wire.writeVarInt(bytes, id);
            for (int i = 0; i < body; i++) bytes.writeByte(random.nextInt(256));
            return bytes;
        }

        void ticks(int ticks) throws Exception {
            ChannelInboundHandler in = (ChannelInboundHandler) inbound.handler();
            ChannelOutboundHandler out = (ChannelOutboundHandler) outbound.handler();
            ChannelPromise promise = fixture.channel.voidPromise();
            for (int t = 0; t < ticks; t++) {
                for (ByteBuf entity : entities) out.write(outbound, entity.retain(), promise);
                in.channelRead(inbound, move.retain());
                in.channelRead(inbound, tickEnd.retain());
                fixture.player.clientTickEnds.incrementAndGet();
                fixture.capture.tickEnded();
                fixture.capture.serverTickEnded(t);
                if (t % FLAG_EVERY == 0) fixture.capture.flagged(flagging, 1);
            }
            CloudCapture.factsChanged(fixture.player);
        }

        @Override
        public void close() {
            for (ByteBuf entity : entities) entity.release();
            move.release();
            tickEnd.release();
            fixture.close();
        }
    }

    @Test
    void costPerPacketByBackend() throws Exception {
        String out = System.getenv("CULTAC_UNCAPTURED_BENCH");
        assumeTrue(out != null && !out.isBlank());
        Binder binder = new Binder();
        Discard uplinks = new Discard();
        List<Lane> lanes = new ArrayList<>();
        try {
            lanes.add(new Lane("captured, list empty", binder, uplinks, "game", List.of(), true));
            lanes.add(new Lane("captured, list names others", binder, uplinks, "game", List.of("hub*"), true));
            lanes.add(new Lane("uncaptured", binder, uplinks, "hub-1", List.of("hub*"), true));
            lanes.add(new Lane("no capture installed", binder, uplinks, "game", List.of(), false));
            long[][] cpu = new long[lanes.size()][REPS];
            long[][] wall = new long[lanes.size()][REPS];
            long[][] alloc = new long[lanes.size()][REPS];
            for (int rep = -WARMUP_REPS; rep < REPS; rep++) {
                for (int l = 0; l < lanes.size(); l++) {
                    Lane lane = lanes.get((l + Math.max(rep, 0)) % lanes.size());
                    int index = lanes.indexOf(lane);
                    long cpu0 = THREADS.getCurrentThreadCpuTime();
                    long alloc0 = THREADS.getCurrentThreadAllocatedBytes();
                    long wall0 = System.nanoTime();
                    for (int pass = 0; pass < PASSES; pass++) lane.ticks(TICKS);
                    long wall1 = System.nanoTime();
                    long alloc1 = THREADS.getCurrentThreadAllocatedBytes();
                    long cpu1 = THREADS.getCurrentThreadCpuTime();
                    if (rep < 0) continue;
                    cpu[index][rep] = cpu1 - cpu0;
                    wall[index][rep] = wall1 - wall0;
                    alloc[index][rep] = alloc1 - alloc0;
                }
            }
            double packets = (double) TICKS * PASSES * PACKETS_PER_TICK;
            StringBuilder report = new StringBuilder("uncaptured bench: " + PACKETS_PER_TICK + " packets a tick, "
                    + TICKS * PASSES + " ticks a rep, " + REPS + " reps, median [min max] per packet\n");
            for (int l = 0; l < lanes.size(); l++) {
                report.append(String.format(
                        "  %-28s cpu %s ns  wall %s ns  alloc %s B%n",
                        lanes.get(l).name,
                        stat(cpu[l], packets),
                        stat(wall[l], packets),
                        stat(alloc[l], packets)));
            }
            report.append("  uploaded bytes ").append(uplinks.bytes).append('\n');
            Files.writeString(
                    Path.of(out), report, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } finally {
            for (Lane lane : lanes) lane.close();
            CloudCapture.uplinks(CloudCapture.Uplinks.NONE);
            CloudCapture.segmentBinder(SegmentBinder.NONE);
            CloudCapture.uncaptured(UncapturedServers.NONE);
        }
    }

    private static String stat(long[] values, double per) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.format(
                "%.1f [%.1f %.1f]", sorted[sorted.length / 2] / per, sorted[0] / per, sorted[sorted.length - 1] / per);
    }
}
