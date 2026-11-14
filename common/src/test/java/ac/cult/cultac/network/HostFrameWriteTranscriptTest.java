package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.logging.PacketLogObserver;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.util.ReferenceCountUtil;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the encoder does with each kind of host frame, with packet logging on and off: the frames it forwards, the
 * input's reference count, the promise, send handlers, the packet log and reported failures, for routed, unrouted,
 * standalone and undecoded frames and for empty, truncated, overlong and unknown packet IDs.
 */
class HostFrameWriteTranscriptTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eachHostFrameMatchesItsTranscript(boolean translated) throws Exception {
        String resource = "host-frame-writes-" + (translated ? "translated" : "native") + ".txt";
        List<String> expected;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
        }
        var actual = new ArrayList<String>();
        for (boolean logging : new boolean[] {false, true}) actual.addAll(transcript(translated, logging));
        assertEquals(String.join("\n", expected), String.join("\n", actual));
    }

    private static Map<String, Function<RecordReceiveFixture, ByteBuf>> frames() {
        var frames = new LinkedHashMap<String, Function<RecordReceiveFixture, ByteBuf>>();
        frames.put("routed ping", f -> packet(f, "ping").writeInt(77));
        frames.put("routed ping, truncated", f -> packet(f, "ping"));
        frames.put(
                "unrouted set_time",
                f -> packet(f, "set_time").writeLong(1).writeLong(2).writeBoolean(true));
        frames.put("standalone keep_alive", f -> packet(f, "keep_alive").writeLong(9));
        frames.put("custom_payload", f -> {
            ByteBuf frame = packet(f, "custom_payload");
            byte[] channel = "minecraft:brand".getBytes(StandardCharsets.UTF_8);
            Wire.writeVarInt(frame, channel.length);
            return frame.writeBytes(channel).writeByte(3).writeBytes(new byte[] {'a', 'b', 'c'});
        });
        frames.put("empty", f -> Unpooled.buffer(0));
        frames.put("one continuation byte", f -> Unpooled.wrappedBuffer(new byte[] {(byte) 0x80}));
        frames.put(
                "five continuation bytes",
                f -> Unpooled.wrappedBuffer(
                        new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}));
        frames.put(
                "ID -1",
                f -> Unpooled.wrappedBuffer(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F, 1}));
        frames.put("ID 127", f -> Unpooled.wrappedBuffer(new byte[] {0x7F, 1}));
        frames.put("routed ping after", f -> packet(f, "ping").writeInt(78));
        return frames;
    }

    private static List<String> transcript(boolean translated, boolean logging) throws Exception {
        var log = new ArrayList<String>();
        try (var services = new RecordConsumerServices();
                var failures = new ReportedFailures();
                var f = translated
                        ? RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11)
                        : new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            services.useNetwork(f.manager);
            f.user.bind(mock(PlatformPlayer.class));
            f.player.nettyScheduler.removeScheduler();
            var pings = new HashMap<Integer, Integer>();
            f.connection.packetObserver(new PacketLogObserver() {
                @Override
                public boolean active() {
                    return logging;
                }

                @Override
                public void packet(PacketDirection direction, ConnectionPhase phase, ByteBuf frame) {
                    log.add("  logged " + direction + " " + phase + " " + frame(f, pings, frame));
                }

                @Override
                public void stop(String reason) {
                    log.add("  log stopped " + reason);
                }
            });
            f.registrar.register(routes -> routes.send(
                    ClientboundPackets.PING,
                    PacketListenerPriority.NORMAL,
                    (event, player, packet) ->
                            log.add("  send ping#" + pings.computeIfAbsent(packet.id(), ignored -> pings.size()))));
            for (var entry : frames().entrySet()) {
                log.add((logging ? "logging " : "") + entry.getKey());
                // Reports are throttled per operation; a fresh throttle lets every frame show its own.
                CultNetworkManager.failureThrottle(new LogThrottle());
                int reported = failures.messages().size();
                ByteBuf frame = entry.getValue().apply(f);
                ChannelFuture written = f.channel.writeAndFlush(frame);
                pump(f);
                Object message;
                var outputs = new ArrayList<Object>();
                while ((message = f.channel.readOutbound()) != null) outputs.add(message);
                for (Object output : outputs)
                    log.add("  out " + (output == frame ? "same " : "")
                            + (output instanceof ByteBuf buf
                                    ? frame(f, pings, buf)
                                    : output.getClass().getSimpleName()));
                log.add("  input refCnt " + frame.refCnt() + ", promise " + outcome(written));
                for (Object output : outputs) ReferenceCountUtil.release(output);
                if (frame.refCnt() > 0) frame.release(frame.refCnt());
                var reports = failures.messages();
                for (String failure : reports.subList(reported, reports.size())) log.add("  reported " + failure);
                log.add("  open " + f.channel.isOpen());
            }
        }
        return log;
    }

    private static ByteBuf packet(RecordReceiveFixture f, String name) {
        return f.id(ClientboundPackets.PING, name);
    }

    /** As in {@link OutboundStreamTranscriptTest}: a ping's random ID is replaced by its order of first appearance. */
    private static String frame(RecordReceiveFixture f, Map<Integer, Integer> pings, ByteBuf frame) {
        if (!frame.isReadable()) return "(empty)";
        ByteBuf view = frame.duplicate();
        int id;
        try {
            id = Wire.readVarInt(view);
        } catch (RuntimeException unreadable) {
            return ByteBufUtil.hexDump(frame);
        }
        boolean ping = view.readableBytes() == Integer.BYTES
                && id
                        == f.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                                .id("minecraft:ping");
        if (!ping) return ByteBufUtil.hexDump(frame);
        int order = pings.computeIfAbsent(view.readInt(), ignored -> pings.size());
        return Integer.toHexString(id) + " ping#" + order;
    }

    private static void pump(RecordReceiveFixture f) {
        for (int i = 0; i < 8; i++) f.channel.runPendingTasks();
        f.channel.checkException();
    }

    private static String outcome(ChannelFuture future) {
        if (!future.isDone()) return "pending";
        if (future.isSuccess()) return "ok";
        return "failed " + future.cause().getClass().getSimpleName();
    }
}
