package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.network.event.PacketListenerPriority;
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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every frame, flush, promise outcome and after-send task of a scripted outbound stream, against the transcript the
 * encoder produced before its queues changed: host and requested bundles, nested writes from send handlers, native and
 * translated packets, a reconfiguration, and a close while writes are pending.
 */
class OutboundStreamTranscriptTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theScriptedStreamMatchesItsTranscript(boolean translated) throws Exception {
        String resource = "outbound-stream-" + (translated ? "translated" : "native") + ".txt";
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
        try (var services = new RecordConsumerServices();
                var f = translated
                        ? RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11)
                        : new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            services.useNetwork(f.manager);
            f.user.bind(mock(PlatformPlayer.class));
            f.player.nettyScheduler.removeScheduler();
            boolean[] closeAtNext = {false};
            Runnable[] reenter = {null};
            int[] writes = {0};
            var pings = new HashMap<Integer, Integer>();
            f.channel.pipeline().addFirst("transcript", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    int index = writes[0]++;
                    log.add("write " + index + " "
                            + (message instanceof ByteBuf frame
                                    ? frame(f, pings, frame)
                                    : message.getClass().getSimpleName()));
                    promise.addListener(future -> log.add("wire " + index + " " + outcome((ChannelFuture) future)));
                    if (reenter[0] != null) {
                        Runnable write = reenter[0];
                        reenter[0] = null;
                        write.run();
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
            f.registrar.register(routes -> {
                routes.send(ClientboundPackets.SET_CAMERA, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                    int id = packet.entityId();
                    log.add("send camera " + id);
                    if (id % 2 == 0) player.appendFlushProofTransaction(event);
                    if (id % 3 == 0) {
                        f.connection.write(new CultWrite(new ClientboundEntityMotion(id, new Vec3d(id, 0, 0)), false));
                        event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(1000 + id), false));
                    }
                    if (id % 5 == 0) event.bundle();
                    event.getTasksAfterSend().add(() -> log.add("task camera " + id));
                    // Enough tasks, one of them writing again, that the encoder's task queue wraps and grows.
                    for (int t = 0; id % 7 == 0 && t < 7; t++) {
                        int task = t;
                        event.getTasksAfterSend().add(() -> {
                            log.add("task camera " + id + "." + task);
                            if (task == 0) f.channel.write(camera(f, 26));
                        });
                    }
                    if (id % 13 == 0) event.getTasksAfterSend().add(() -> log.add("extra task camera " + id));
                });
                routes.send(
                        ClientboundPackets.ENTITY_MOTION, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                            log.add("send motion " + packet.entityId());
                            player.runAtTrailingProof(event, () -> log.add("proof motion " + packet.entityId()));
                        });
                routes.send(ClientboundPackets.BLOCK_EVENT, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                    log.add("send block " + packet.action() + " " + packet.parameter());
                    event.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(2000 + packet.action()), false));
                });
            });
            var callers = new ArrayList<ChannelFuture>();
            // A host bundle with an unlistened packet inside, then unflushed cameras and a flush.
            callers.add(f.channel.write(f.id(ClientboundPackets.BUNDLE_DELIMITER, "bundle_delimiter")));
            callers.add(f.channel.write(camera(f, 2)));
            callers.add(f.channel.write(f.id(ClientboundPackets.SET_CAMERA, "sound")));
            callers.add(f.channel.write(camera(f, 3)));
            callers.add(f.channel.write(f.id(ClientboundPackets.BUNDLE_DELIMITER, "bundle_delimiter")));
            // The host writes again from the head while the encoder still holds camera 4's after-send task.
            reenter[0] = () -> callers.add(f.channel.write(camera(f, 25)));
            callers.add(f.channel.write(camera(f, 4)));
            callers.add(f.channel.write(camera(f, 5)));
            f.channel.flush();
            // A packet read through model values on an older wire, a model write, a keep-alive and a requested bundle.
            callers.add(f.channel.write(blockEvent(f, 7)));
            f.connection.write(new CultWrite(new ClientboundEntityMotion(8, new Vec3d(0, 1, 0)), false));
            f.connection.executeAfterWrites(() -> log.add("after writes"));
            callers.add(f.channel.write(
                    f.id(ClientboundPackets.KEEP_ALIVE, "keep_alive").writeLong(9)));
            callers.add(f.channel.writeAndFlush(camera(f, 6)));
            callers.add(f.channel.writeAndFlush(camera(f, 10)));
            pump(f);
            // Reconfiguration: the clientbound phase follows the terminal packet, then returns to play.
            callers.add(f.channel.write(f.id(ClientboundPackets.START_CONFIGURATION, "start_configuration")));
            callers.add(f.channel.writeAndFlush(
                    f.id(ClientboundPackets.KEEP_ALIVE, "keep_alive").writeLong(12)));
            log.add("phase " + f.connection.phase(PacketDirection.CLIENTBOUND));
            pump(f);
            f.phase(ConnectionPhase.CONFIGURATION);
            callers.add(f.channel.write(f.id(ClientboundPackets.FINISH_CONFIGURATION, "finish_configuration")));
            f.channel.flush();
            pump(f);
            f.phase(ConnectionPhase.PLAY);
            callers.add(f.channel.write(camera(f, 14)));
            callers.add(f.channel.write(camera(f, 15)));
            callers.add(f.channel.write(blockEvent(f, 16)));
            // The head closes the channel while the encoder still holds frames for this flush.
            callers.add(f.channel.write(camera(f, 18)));
            closeAtNext[0] = true;
            callers.add(f.channel.write(camera(f, 21)));
            callers.add(f.channel.writeAndFlush(camera(f, 24)));
            callers.add(f.channel.writeAndFlush(camera(f, 30)));
            pump(f);
            for (int i = 0; i < callers.size(); i++) log.add("caller " + i + " " + outcome(callers.get(i)));
            log.add("open " + f.channel.isOpen());
            ByteBuf frame;
            while ((frame = f.channel.readOutbound()) != null) {
                log.add("out " + frame(f, pings, frame));
                frame.release();
            }
        }
        return log;
    }

    private static ByteBuf camera(RecordReceiveFixture f, int entityId) {
        ByteBuf frame = f.id(ClientboundPackets.SET_CAMERA, "set_camera");
        Wire.writeVarInt(frame, entityId);
        return frame;
    }

    private static ByteBuf blockEvent(RecordReceiveFixture f, int action) {
        ByteBuf frame = f.id(ClientboundPackets.BLOCK_EVENT, "block_event");
        frame.writeLong(0x1234L).writeByte(action).writeByte(action + 1);
        Wire.writeVarInt(frame, 1);
        return frame;
    }

    /**
     * The frame's bytes, a ping's random ID replaced by its order of first appearance; the wire IDs of pings vary
     * per run, while which pings are the same ping does not.
     */
    private static String frame(RecordReceiveFixture f, Map<Integer, Integer> pings, ByteBuf frame) {
        ByteBuf view = frame.duplicate();
        int id = Wire.readVarInt(view);
        boolean ping = view.readableBytes() == Integer.BYTES
                && (id
                                == f.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                                        .id("minecraft:ping")
                        || id
                                == f.data.packets(ConnectionPhase.CONFIGURATION, PacketDirection.CLIENTBOUND)
                                        .id("minecraft:ping"));
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
