package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every frame, flush, promise outcome, after-send task and buffer reference count of a stream whose own-route send
 * events nest three deep, are held or released in varying order, cancelled, or thrown from mid-expansion, with
 * writes the socket refuses and a close while writes are pending, against the base encoder's transcript.
 */
class NestedSendTranscriptTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theNestedStreamMatchesItsTranscript(boolean translated) throws Exception {
        String resource = "nested-send-" + (translated ? "translated" : "native") + ".txt";
        List<String> expected;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
        }
        assertEquals(String.join("\n", expected), String.join("\n", transcript(translated)));
    }

    private static List<String> transcript(boolean translated) throws Exception {
        var log = new ArrayList<String>();
        List<ByteBuf> allocated = new ArrayList<>();
        List<ByteBuf> reached = new ArrayList<>();
        try (var services = new RecordConsumerServices();
                var f = translated
                        ? RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11)
                        : new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            services.useNetwork(f.manager);
            // A released unpooled frame keeps a zero count; a pooled one may be handed out again as a later frame.
            f.channel.config().setAllocator(UnpooledByteBufAllocator.DEFAULT);
            f.user.bind(mock(PlatformPlayer.class));
            f.player.nettyScheduler.removeScheduler();
            boolean[] closeAtNext = {false};
            int[] refuse = {0};
            int[] writes = {0};
            var pings = new HashMap<Integer, Integer>();
            f.channel.pipeline().addFirst("transcript", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    int index = writes[0]++;
                    if (message instanceof ByteBuf frame) reached.add(frame);
                    log.add("write " + index + " "
                            + (message instanceof ByteBuf frame
                                    ? frame(f, pings, frame)
                                    : message.getClass().getSimpleName()));
                    promise.addListener(future -> log.add("wire " + index + " " + outcome((ChannelFuture) future)));
                    if (refuse[0] > 0) {
                        refuse[0]--;
                        ReferenceCountUtil.release(message);
                        promise.setFailure(new IOException("refused"));
                        return;
                    }
                    if (closeAtNext[0]) {
                        closeAtNext[0] = false;
                        ctx.close();
                    }
                    ctx.write(message, promise);
                }

                @Override
                public void flush(ChannelHandlerContext ctx) {
                    log.add("flush");
                    ctx.flush();
                }
            });
            f.registrar.registerOwn(routes -> {
                routes.send(ClientboundPackets.KEEP_ALIVE, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                    int k = (int) packet.id();
                    log.add(state(event) + " keep_alive " + k);
                    if (k % 2 == 0) event.getWritesBeforeSend().add(ping(k * 100 + 1));
                    event.getWritesAfterSend().add(ping(k * 100 + 2));
                    if (k % 3 == 0) event.getWritesAfterSend().add(ping(k * 100 + 3));
                    if (k % 4 == 1) event.getTasksAfterSend().add(() -> log.add("task keep_alive " + k));
                    if (k % 5 == 0) event.bundle();
                    if (k % 7 == 0) event.setCancelled(true);
                    if (k % 11 == 0) throw new IllegalStateException("keep_alive " + k);
                });
                routes.send(ClientboundPackets.PING, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                    int p = packet.id();
                    log.add(state(event) + " ping " + p);
                    if (p < 100) return;
                    int k = p / 100, j = p % 100;
                    if (k % 3 == 0) event.getWritesBeforeSend().add(motion(p + 50));
                    if (j != 3) event.getWritesAfterSend().add(motion(p));
                    if (k % 3 == 1) event.getTasksAfterSend().add(() -> log.add("task ping " + p));
                    if (j == 3 && k % 2 == 0) event.setCancelled(true);
                    if (j == 2 && k % 4 == 2) throw new IllegalStateException("ping " + p);
                });
                routes.send(
                        ClientboundPackets.ENTITY_MOTION, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                            int m = packet.entityId(), k = m / 100;
                            log.add(state(event) + " motion " + m);
                            if (k % 2 == 1) event.getTasksAfterSend().add(() -> log.add("task motion " + m));
                            if (k % 5 == 1) event.setCancelled(true);
                            if (k % 6 == 5) {
                                event.getWritesAfterSend().add(ping(9));
                                throw new IllegalStateException("motion " + m);
                            }
                        });
            });
            var callers = new ArrayList<ChannelFuture>();
            for (int k = 1; k <= 12; k++) {
                ByteBuf frame = keepAlive(f, k, allocated);
                callers.add(k % 3 == 0 ? f.channel.writeAndFlush(frame) : f.channel.write(frame));
            }
            f.channel.flush();
            pump(f, log);
            refs(log, "flushed", allocated, reached);
            // The socket refuses the next writes it is handed.
            refuse[0] = 3;
            for (int k = 13; k <= 16; k++) callers.add(f.channel.writeAndFlush(keepAlive(f, k, allocated)));
            pump(f, log);
            refs(log, "refused", allocated, reached);
            // The head closes the channel while the encoder still holds frames for this flush.
            for (int k = 17; k <= 20; k++) {
                if (k == 19) closeAtNext[0] = true;
                callers.add(f.channel.write(keepAlive(f, k, allocated)));
            }
            callers.add(f.channel.writeAndFlush(keepAlive(f, 21, allocated)));
            callers.add(f.channel.writeAndFlush(keepAlive(f, 22, allocated)));
            pump(f, log);
            refs(log, "closed", allocated, reached);
            for (int i = 0; i < callers.size(); i++) log.add("caller " + i + " " + outcome(callers.get(i)));
            log.add("open " + f.channel.isOpen());
            ByteBuf frame;
            while ((frame = f.channel.readOutbound()) != null) {
                log.add("out " + frame(f, pings, frame));
                frame.release();
            }
            refs(log, "drained", allocated, reached);
        }
        return log;
    }

    private static CultWrite ping(int id) {
        return new CultWrite(new ClientboundPing(id), false);
    }

    private static CultWrite motion(int entityId) {
        return new CultWrite(new ClientboundEntityMotion(entityId, new Vec3d(entityId, 0, 0)), false);
    }

    private static ByteBuf keepAlive(RecordReceiveFixture f, int id, List<ByteBuf> allocated) {
        ByteBuf frame = f.id(ClientboundPackets.KEEP_ALIVE, "keep_alive").writeLong(id);
        allocated.add(frame);
        return frame;
    }

    /** The reference count of every frame the test wrote and every frame that reached the head. */
    private static void refs(List<String> log, String when, List<ByteBuf> allocated, List<ByteBuf> reached) {
        StringBuilder line = new StringBuilder("refs ").append(when).append(" written");
        for (ByteBuf frame : allocated) line.append(' ').append(frame.refCnt());
        line.append(" reached");
        for (ByteBuf frame : reached) line.append(' ').append(frame.refCnt());
        log.add(line.toString());
    }

    /** "fresh" when the event holds nothing a previous dispatch left. */
    private static String state(PacketSendEvent<?> event) {
        boolean fresh = !event.isCancelled()
                && event.writesBefore().isEmpty()
                && event.writesAfter().isEmpty()
                && event.tasksAfter().isEmpty()
                && !event.isBundleRequested()
                && event.leadingPing() == null
                && event.trackedPing() == 0
                && event.receiptProof() == 0
                && event.getPacket() == event.getOriginalPacket()
                && !event.isReplaced();
        return (fresh ? "fresh" : "stale") + (event.isAuthored() ? " authored" : "");
    }

    /**
     * The frame's bytes, a ping's ID replaced by its order of first appearance; the wire IDs of pings vary per run,
     * while which pings are the same ping does not.
     */
    private static String frame(RecordReceiveFixture f, Map<Integer, Integer> pings, ByteBuf frame) {
        ByteBuf view = frame.duplicate();
        int id = Wire.readVarInt(view);
        boolean ping = view.readableBytes() == Integer.BYTES
                && (id == f.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:ping")
                        || id
                                == f.data.packets(ConnectionPhase.CONFIGURATION, PacketDirection.CLIENTBOUND)
                                        .id("minecraft:ping"));
        if (!ping) return ByteBufUtil.hexDump(frame);
        int order = pings.computeIfAbsent(view.readInt(), ignored -> pings.size());
        return Integer.toHexString(id) + " ping#" + order;
    }

    private static void pump(RecordReceiveFixture f, List<String> log) {
        for (int i = 0; i < 8; i++) f.channel.runPendingTasks();
        try {
            f.channel.checkException();
        } catch (Throwable failure) {
            log.add("exception " + failure.getClass().getSimpleName());
        }
    }

    private static String outcome(ChannelFuture future) {
        if (!future.isDone()) return "pending";
        if (future.isSuccess()) return "ok";
        return "failed " + future.cause().getClass().getSimpleName();
    }
}
