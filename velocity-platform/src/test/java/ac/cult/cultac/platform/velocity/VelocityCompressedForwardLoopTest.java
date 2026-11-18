package ac.cult.cultac.platform.velocity;

import static ac.cult.cultac.platform.velocity.CompressedFrames.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import ac.cult.cultac.platform.velocity.VelocityBackendBurstDeliveryTest.BackendConnection;
import ac.cult.cultac.platform.velocity.VelocityBackendBurstDeliveryTest.BackendServer;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Local channels on one event loop: real reads and read completion, links installed from Velocity's connected event,
 * and the read resumed by a task queued after the event, as Velocity's backend transition does.
 */
class VelocityCompressedForwardLoopTest {
    private static final int THRESHOLD = 256;

    private EventLoopGroup group;
    private Channel serverChannel;
    private final BlockingQueue<Channel> accepted = new ArrayBlockingQueue<>(8);
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        var address = new LocalAddress("compressed-forward-" + UUID.randomUUID());
        serverChannel = new ServerBootstrap()
                .group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        accepted.add(channel);
                    }
                })
                .bind(address)
                .sync()
                .channel();
    }

    @AfterEach
    void stop() throws Exception {
        serverChannel.close().sync();
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
    }

    /** A proxy-side channel and the remote end it is connected to. */
    private record Pair(Channel proxySide, Channel remote) {}

    private Pair connect(boolean autoRead) throws Exception {
        Channel proxySide = new Bootstrap()
                .group(group)
                .channel(LocalChannel.class)
                .option(ChannelOption.AUTO_READ, autoRead)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(serverChannel.localAddress())
                .sync()
                .channel();
        return new Pair(proxySide, accepted.poll(5, TimeUnit.SECONDS));
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return group.next().submit(task).get(5, TimeUnit.SECONDS);
    }

    private Pair client() throws Exception {
        Pair pair = connect(true);
        receive(pair);
        onLoop(() -> {
            var pipeline = pair.proxySide().pipeline();
            pipeline.addLast("cipher-encoder", new CipherEncoder());
            pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD));
            pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
            pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
            return VelocityCompressedForward.install(
                    pipeline, CompressEncoder.class.getName(), CompressDecoder.class.getName(), true, false, true);
        });
        return pair;
    }

    /** Collects what the client end of {@code pair} receives into {@link #received}. */
    private void receive(Pair pair) {
        pair.remote().pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ByteBuf buf = (ByteBuf) msg;
                try {
                    synchronized (received) {
                        byte[] bytes = new byte[buf.readableBytes()];
                        buf.readBytes(bytes);
                        received.writeBytes(bytes);
                    }
                } finally {
                    buf.release();
                }
            }
        });
    }

    private Pair backend(Channel client) throws Exception {
        Pair pair = connect(false);
        onLoop(() -> {
            var pipeline = pair.proxySide().pipeline();
            pipeline.addLast("frame-decoder", new FrameDecoder());
            pipeline.addLast("read-timeout", new ChannelInboundHandlerAdapter());
            pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
            pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
            pipeline.addLast("handler", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ByteBuf buf = (ByteBuf) msg;
                    try {
                        if (client.isActive()) client.write(buf.retain());
                    } finally {
                        buf.release();
                    }
                }

                @Override
                public void channelReadComplete(ChannelHandlerContext ctx) {
                    client.flush();
                }
            });
            return null;
        });
        return pair;
    }

    private List<ClientFrame> awaitFrames(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            byte[] bytes;
            synchronized (received) {
                bytes = received.toByteArray();
            }
            try {
                var frames = read(bytes, THRESHOLD);
                if (frames.size() >= count) return frames;
            } catch (RuntimeException | AssertionError incomplete) {
                if (System.nanoTime() > deadline) throw incomplete;
            }
            if (System.nanoTime() > deadline) fail("expected " + count + " frames");
            Thread.onSpinWait();
        }
    }

    public abstract static class InFlightPlayer implements Player {
        public abstract Object getConnectionInFlight();
    }

    @Test
    void connectedEventLinksBeforeTheResumedReadAndASecondBackendReplacesIt() throws Exception {
        var proxy = mock(ProxyServer.class);
        var events = mock(EventManager.class);
        when(proxy.getEventManager()).thenReturn(events);
        Object plugin = new Object();
        Pair client = client();
        var forward = new VelocityCompressedForward(
                proxy, plugin, player -> client.proxySide(), true, false, true, CompressDecoder.class.getName());
        forward.listen();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventHandler<ServerConnectedEvent>> connected = ArgumentCaptor.forClass(EventHandler.class);
        verify(events).register(eq(plugin), eq(ServerConnectedEvent.class), connected.capture());
        verify(events).register(eq(plugin), eq(ServerPostConnectEvent.class), any());

        var player = mock(InFlightPlayer.class);
        var forwarder = (CompressedForwarder) client.proxySide().pipeline().get(CompressedForwarder.NAME);
        byte[] first = packet(20_000, 1);
        byte[] second = packet(9_000, 2);

        Pair backend = backend(client.proxySide());
        joinGame(connected.getValue(), player, backend, first);
        var frames = awaitFrames(1);
        assertPackets(List.of(first), frames);
        assertArrayEquals(body(first, THRESHOLD), frames.getFirst().body());
        assertEquals(1L, onLoop(forwarder::forwarded));

        Pair next = backend(client.proxySide());
        joinGame(connected.getValue(), player, next, second);
        assertPackets(List.of(first, second), awaitFrames(2));
        assertEquals(2L, onLoop(forwarder::forwarded));
        assertNull(onLoop(() -> backend.proxySide().pipeline().get(CompressedKeep.NAME)), "one link per client");
        assertNotNull(onLoop(() -> next.proxySide().pipeline().get(CompressedKeep.NAME)));

        onLoop(() -> {
            VelocityCompressedForward.uninstall(client.proxySide());
            return null;
        });
        assertNull(onLoop(() -> client.proxySide().pipeline().get(CompressedForwarder.NAME)));
        assertNull(onLoop(() -> next.proxySide().pipeline().get(CompressedKeep.NAME)));
        assertNull(onLoop(() -> next.proxySide().pipeline().get(CompressedMark.NAME)));
        assertPackets(List.of(first, second), awaitFrames(2));

        forward.close();
        verify(events).unregister(plugin, connected.getValue());
    }

    @Test
    void encoderOnItsOwnExecutorInstallsNothingAndWritesKeepTheirOrder() throws Exception {
        var encoders = new DefaultEventExecutorGroup(1);
        try {
            Pair client = connect(true);
            receive(client);
            CompressedForwarder forwarder = onLoop(() -> {
                var pipeline = client.proxySide().pipeline();
                pipeline.addLast("cipher-encoder", new CipherEncoder());
                pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                pipeline.addLast(encoders, CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD));
                pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
                pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
                return VelocityCompressedForward.install(
                        pipeline, CompressEncoder.class.getName(), CompressDecoder.class.getName(), true, false, true);
            });
            assertNull(forwarder);
            assertNull(onLoop(() -> client.proxySide().pipeline().get(CompressedForwarder.NAME)));
            byte[] large = packet(3000, 90);
            byte[] small = packet(100, 91);
            var busy = new CountDownLatch(1);
            encoders.next().execute(() -> {
                try {
                    busy.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            onLoop(() -> {
                client.proxySide().write(Unpooled.wrappedBuffer(large));
                client.proxySide().write(Unpooled.wrappedBuffer(small));
                client.proxySide().flush();
                return null;
            });
            busy.countDown();
            assertPackets(List.of(large, small), awaitFrames(2));
            client.proxySide().close().sync();
        } finally {
            encoders.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    @Test
    void decoderOnItsOwnExecutorLinksNothingAndReadsKeepTheirOrder() throws Exception {
        var decoders = new DefaultEventExecutorGroup(1);
        try {
            Pair client = client();
            var forwarder = (CompressedForwarder) client.proxySide().pipeline().get(CompressedForwarder.NAME);
            assertNotNull(forwarder);
            Pair backend = connect(false);
            onLoop(() -> {
                var pipeline = backend.proxySide().pipeline();
                pipeline.addLast("frame-decoder", new FrameDecoder());
                pipeline.addLast(
                        decoders, VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                pipeline.addLast("handler", relay(client.proxySide()));
                VelocityCompressedForward.link(
                        forwarder, client.proxySide(), backend.proxySide(), CompressDecoder.class.getName());
                return null;
            });
            assertEquals("backend-executor", onLoop(forwarder::linkStatus));
            assertNull(onLoop(() -> backend.proxySide().pipeline().get(CompressedKeep.NAME)));
            assertNull(onLoop(() -> backend.proxySide().pipeline().get(CompressedMark.NAME)));
            byte[] large = packet(3000, 92);
            byte[] small = packet(100, 93);
            backend.remote()
                    .writeAndFlush(Unpooled.wrappedBuffer(
                            concat(frame(body(large, THRESHOLD)), frame(body(small, THRESHOLD)))))
                    .sync();
            backend.proxySide().eventLoop().execute(() -> {
                backend.proxySide().config().setAutoRead(true);
                backend.proxySide().read();
            });
            assertPackets(List.of(large, small), awaitFrames(2));
            assertEquals(0L, onLoop(forwarder::forwarded));
            backend.proxySide().close().sync();
        } finally {
            decoders.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    /** Writes every inflated buffer to {@code client} and flushes it when the read completes. */
    private static ChannelInboundHandlerAdapter relay(Channel client) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ByteBuf buf = (ByteBuf) msg;
                try {
                    if (client.isActive()) client.write(buf.retain());
                } finally {
                    buf.release();
                }
            }

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                client.flush();
            }
        };
    }

    /** With the backend's frame waiting, queues the event's install and then the resume, in Velocity's order. */
    private void joinGame(
            EventHandler<ServerConnectedEvent> handler, InFlightPlayer player, Pair backend, byte[] packet)
            throws Exception {
        var registered = mock(RegisteredServer.class);
        var connection = mock(BackendServer.class);
        when(connection.getConnection()).thenReturn(new BackendConnection(backend.proxySide()));
        when(connection.getServer()).thenReturn(registered);
        when(player.getConnectionInFlight()).thenReturn(connection);
        backend.remote()
                .writeAndFlush(Unpooled.wrappedBuffer(frame(body(packet, THRESHOLD))))
                .sync();
        handler.execute(new ServerConnectedEvent(player, registered, null));
        backend.proxySide().eventLoop().execute(() -> {
            backend.proxySide().config().setAutoRead(true);
            backend.proxySide().read();
        });
    }
}
