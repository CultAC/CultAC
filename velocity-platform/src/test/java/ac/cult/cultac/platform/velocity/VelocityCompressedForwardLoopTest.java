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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import java.io.ByteArrayOutputStream;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private ResourceLeakDetector.Level level;

    @BeforeEach
    void start() throws Exception {
        // Velocity starts with netty's leak detection disabled, the only level at which the keep holds frames.
        level = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
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
        try {
            serverChannel.close().sync();
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        } finally {
            ResourceLeakDetector.setLevel(level);
        }
    }

    /** A proxy-side channel and the remote end it is connected to. */
    private record Pair(Channel proxySide, Channel remote) {}

    private Pair connect(boolean autoRead) throws Exception {
        Channel proxySide = new Bootstrap()
                .group(group)
                .channel(LocalChannel.class)
                .option(ChannelOption.AUTO_READ, autoRead)
                // Socket transports allocate with the default allocator; local channels wrap it.
                .option(ChannelOption.ALLOCATOR, ByteBufAllocator.DEFAULT)
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
        return client(true);
    }

    /** A client with Velocity's handlers, and the forwarder installed when {@code forward}. */
    private Pair client(boolean forward) throws Exception {
        Pair pair = connect(true);
        receive(pair);
        onLoop(() -> {
            var pipeline = pair.proxySide().pipeline();
            pipeline.addLast("cipher-encoder", new CipherEncoder());
            var compressor = new SharedCompressor();
            pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD, true, compressor));
            pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
            pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
            return forward
                    ? VelocityCompressedForward.install(
                            pipeline, CompressEncoder.class, CompressDecoder.class, COMPRESSORS)
                    : null;
        });
        return pair;
    }

    /** As {@link #backend(Channel)} on {@code loop}'s next loop, with {@code handler} in place of the relay. */
    private Pair backend(EventLoopGroup loop, ChannelInboundHandlerAdapter handler) throws Exception {
        Channel proxySide = new Bootstrap()
                .group(loop)
                .channel(LocalChannel.class)
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.ALLOCATOR, ByteBufAllocator.DEFAULT)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(serverChannel.localAddress())
                .sync()
                .channel();
        Pair pair = new Pair(proxySide, accepted.poll(5, TimeUnit.SECONDS));
        proxySide
                .eventLoop()
                .submit(() -> {
                    var pipeline = proxySide.pipeline();
                    pipeline.addLast("frame-decoder", new FrameDecoder());
                    pipeline.addLast("read-timeout", new ChannelInboundHandlerAdapter());
                    pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                    pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
                    pipeline.addLast("handler", handler);
                })
                .get(5, TimeUnit.SECONDS);
        return pair;
    }

    /** Sends {@code packet} compressed from {@code backend}'s server end and starts the proxy side's reads. */
    private static void serve(Pair backend, byte[] packet) throws Exception {
        backend.remote()
                .writeAndFlush(Unpooled.wrappedBuffer(frame(body(packet, THRESHOLD))))
                .sync();
        backend.proxySide().eventLoop().execute(() -> {
            backend.proxySide().config().setAutoRead(true);
            backend.proxySide().read();
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static <T> T within(Channel channel, Callable<T> task) throws Exception {
        return channel.eventLoop().submit(task).get(5, TimeUnit.SECONDS);
    }

    @Test
    void oldBackendMovedToAnotherLoopPublishesNothingWhileTheNextBackendPublishesAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        byte[] old = packet(4000, 97);
        byte[] next = packet(3000, 98);
        List<byte[]> sent = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = client(on);
                Channel toClient = client.proxySide();
                var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                if (on) assertNotNull(forwarder);
                var oldRead = new CountDownLatch(1);
                var nextPublished = new CountDownLatch(1);
                var oldDone = new CountDownLatch(1);
                Pair oldBackend = backend(group, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf buf = (ByteBuf) msg;
                        try {
                            toClient.writeAndFlush(buf.retain());
                            oldRead.countDown();
                            // The old backend's read stays inside its fire until the next backend's has published.
                            await(nextPublished);
                        } finally {
                            buf.release();
                        }
                    }

                    @Override
                    public void channelReadComplete(ChannelHandlerContext ctx) {
                        oldDone.countDown();
                    }
                });
                Pair nextBackend = backend(group, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf buf = (ByteBuf) msg;
                        try {
                            toClient.writeAndFlush(buf.retain());
                            nextPublished.countDown();
                            // The next backend's read restores its mark only after the old backend's read ends.
                            await(oldDone);
                        } finally {
                            buf.release();
                        }
                    }
                });
                if (on) {
                    within(toClient, () -> {
                        VelocityCompressedForward.link(
                                forwarder, toClient, oldBackend.proxySide(), CompressDecoder.class, COMPRESSORS);
                        return null;
                    });
                    assertEquals("linked", within(toClient, forwarder::linkStatus));
                }
                oldBackend.proxySide().deregister().sync();
                second.register(oldBackend.proxySide()).sync();
                assertNotSame(toClient.eventLoop(), oldBackend.proxySide().eventLoop());
                serve(oldBackend, old);
                await(oldRead);
                if (on) {
                    within(toClient, () -> {
                        VelocityCompressedForward.link(
                                forwarder, toClient, nextBackend.proxySide(), CompressDecoder.class, COMPRESSORS);
                        return null;
                    });
                    assertEquals("linked", within(toClient, forwarder::linkStatus));
                }
                serve(nextBackend, next);
                var frames = awaitFrames(2);
                assertPackets(List.of(old, next), frames);
                sent.add(frames.getFirst().body());
                if (on) {
                    assertArrayEquals(body(next, THRESHOLD), frames.get(1).body(), "the next backend's frame as sent");
                    assertEquals(1L, within(toClient, forwarder::forwarded));
                    assertNull(within(toClient, () -> forwarder.markBuf), "no mark outlives the reads");
                    assertNull(within(toClient, forwarder::markFrame), "every private frame is taken or released");
                    assertNull(
                            within(
                                    oldBackend.proxySide(),
                                    () -> oldBackend.proxySide().pipeline().get(CompressedKeep.NAME)),
                            "the old keep is removed on its own loop");
                }
                oldBackend.proxySide().close().sync();
                nextBackend.proxySide().close().sync();
                toClient.close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the old frame the encoder compresses with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void clientDeregisteredBeforeABackendReadPublishesNothingAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        byte[] old = packet(4000, 101);
        byte[] next = packet(3000, 102);
        List<byte[]> sent = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = client(on);
                Channel toClient = client.proxySide();
                var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                if (on) assertNotNull(forwarder);
                var oldRead = new CountDownLatch(1);
                var nextPublished = new CountDownLatch(1);
                var oldDone = new CountDownLatch(1);
                Pair oldBackend = backend(group, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf buf = (ByteBuf) msg;
                        try {
                            oldRead.countDown();
                            // The old backend's read stays inside its fire until the next backend's has published.
                            await(nextPublished);
                            toClient.writeAndFlush(buf.retain());
                        } finally {
                            buf.release();
                        }
                    }

                    @Override
                    public void channelReadComplete(ChannelHandlerContext ctx) {
                        oldDone.countDown();
                    }
                });
                Pair nextBackend = backend(second, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf buf = (ByteBuf) msg;
                        try {
                            toClient.writeAndFlush(buf.retain());
                            nextPublished.countDown();
                            await(oldDone);
                        } finally {
                            buf.release();
                        }
                    }
                });
                // The server ends run on the loop the old read holds, so the next frame is sent before that read.
                nextBackend
                        .remote()
                        .writeAndFlush(Unpooled.wrappedBuffer(frame(body(next, THRESHOLD))))
                        .sync();
                if (on) {
                    within(toClient, () -> {
                        VelocityCompressedForward.link(
                                forwarder, toClient, oldBackend.proxySide(), CompressDecoder.class, COMPRESSORS);
                        return null;
                    });
                    assertEquals("linked", within(toClient, forwarder::linkStatus));
                }
                toClient.deregister().sync();
                assertFalse(toClient.isRegistered());
                assertSame(oldBackend.proxySide().eventLoop(), toClient.eventLoop());
                serve(oldBackend, old);
                await(oldRead);
                second.register(toClient).sync();
                assertSame(nextBackend.proxySide().eventLoop(), toClient.eventLoop());
                if (on) {
                    within(toClient, () -> {
                        VelocityCompressedForward.link(
                                forwarder, toClient, nextBackend.proxySide(), CompressDecoder.class, COMPRESSORS);
                        return null;
                    });
                    assertEquals("linked", within(toClient, forwarder::linkStatus));
                }
                nextBackend.proxySide().eventLoop().execute(() -> {
                    nextBackend.proxySide().config().setAutoRead(true);
                    nextBackend.proxySide().read();
                });
                var frames = awaitFrames(2);
                assertPackets(List.of(next, old), frames);
                sent.add(frames.get(1).body());
                if (on) {
                    assertArrayEquals(
                            body(next, THRESHOLD), frames.getFirst().body(), "the next backend's frame as sent");
                    assertEquals(1L, within(toClient, forwarder::forwarded));
                    assertNull(within(toClient, () -> forwarder.markBuf), "no mark outlives the reads");
                    assertNull(within(toClient, forwarder::markFrame), "every private frame is taken or released");
                }
                oldBackend.proxySide().close().sync();
                nextBackend.proxySide().close().sync();
                toClient.close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the old frame the encoder compresses with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void clientMovedToAnotherLoopForwardsAgainFromABackendLinkedOnThatLoop() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        byte[] first = packet(4000, 99);
        byte[] after = packet(3000, 100);
        try {
            Pair client = client();
            Channel toClient = client.proxySide();
            var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
            assertNotNull(forwarder);
            Pair firstBackend = backend(group, relay(toClient));
            within(toClient, () -> {
                VelocityCompressedForward.link(
                        forwarder, toClient, firstBackend.proxySide(), CompressDecoder.class, COMPRESSORS);
                return null;
            });
            serve(firstBackend, first);
            assertPackets(List.of(first), awaitFrames(1));
            assertEquals(1L, within(toClient, forwarder::forwarded));

            toClient.deregister().sync();
            second.register(toClient).sync();
            Pair nextBackend = backend(second, relay(toClient));
            assertSame(toClient.eventLoop(), nextBackend.proxySide().eventLoop());
            within(toClient, () -> {
                VelocityCompressedForward.link(
                        forwarder, toClient, nextBackend.proxySide(), CompressDecoder.class, COMPRESSORS);
                return null;
            });
            assertEquals("linked", within(toClient, forwarder::linkStatus));
            serve(nextBackend, after);
            var frames = awaitFrames(2);
            assertPackets(List.of(first, after), frames);
            assertArrayEquals(body(after, THRESHOLD), frames.get(1).body());
            assertEquals(2L, within(toClient, forwarder::forwarded), "a mark published on the new loop is taken there");
            firstBackend.proxySide().close().sync();
            nextBackend.proxySide().close().sync();
            toClient.close().sync();
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
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
                proxy,
                plugin,
                player -> client.proxySide(),
                new VelocityCompressedForward.Codecs(CompressEncoder.class, CompressDecoder.class, COMPRESSORS));
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
                        pipeline, CompressEncoder.class, CompressDecoder.class, COMPRESSORS);
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
                        forwarder, client.proxySide(), backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                return null;
            });
            assertEquals("backend-executor", onLoop(forwarder::linkStatus));
            assertNull(onLoop(() -> backend.proxySide().pipeline().get(CompressedKeep.NAME)));
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

    @Test
    void decoderRemovedFromAnotherThreadDuringAReadLeavesTheReadAsWithTheSwitchOff() throws Exception {
        byte[] first = body(packet(3000, 94), THRESHOLD);
        byte[] second = body(packet(3000, 95), THRESHOLD);
        List<List<byte[]>> got = new ArrayList<>();
        for (boolean forward : new boolean[] {false, true}) {
            synchronized (received) {
                received.reset();
            }
            Pair client = client();
            var forwarder = (CompressedForwarder) client.proxySide().pipeline().get(CompressedForwarder.NAME);
            Pair backend = backend(client.proxySide());
            onLoop(() -> {
                var pipeline = backend.proxySide().pipeline();
                pipeline.addAfter("frame-decoder", "remover", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        ctx.pipeline().remove(this);
                        var remover = new Thread(() -> pipeline.remove(VelocityCompressedForward.COMPRESSION_DECODER));
                        remover.start();
                        remover.join();
                        ctx.fireChannelRead(msg);
                    }
                });
                if (forward)
                    VelocityCompressedForward.link(
                            forwarder, client.proxySide(), backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                return null;
            });
            if (forward) assertEquals("linked", onLoop(forwarder::linkStatus));
            backend.remote()
                    .writeAndFlush(Unpooled.wrappedBuffer(concat(frame(first), frame(second))))
                    .sync();
            backend.proxySide().eventLoop().execute(() -> {
                backend.proxySide().config().setAutoRead(true);
                backend.proxySide().read();
            });
            got.add(packets(awaitFrames(2)));
            if (forward) {
                assertEquals(0L, onLoop(forwarder::forwarded));
                assertEquals("backend-adjacency", onLoop(forwarder::linkStatus));
            }
            backend.proxySide().close().sync();
            client.proxySide().close().sync();
        }
        for (List<byte[]> packets : got) {
            assertEquals(2, packets.size());
            assertArrayEquals(first, packets.get(0), "the first frame reaches the next handler compressed");
            assertArrayEquals(second, packets.get(1));
        }
    }

    @Test
    void handlerAddedOnAnotherExecutorWhileTheClientInstallWaitsGetsNoForwarderAsWithTheSwitchOff() throws Exception {
        var foreign = new DefaultEventExecutorGroup(1);
        try {
            List<List<String>> seen = new ArrayList<>();
            for (boolean on : new boolean[] {false, true}) {
                Pair client = connect(true);
                ChannelPipeline pipeline = client.proxySide().pipeline();
                Thread loop = onLoop(() -> {
                    var compressor = new SharedCompressor();
                    pipeline.addLast("cipher-encoder", new CipherEncoder());
                    pipeline.addLast(
                            VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
                    pipeline.addLast(
                            CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD, true, compressor));
                    pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
                    pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
                    return Thread.currentThread();
                });
                List<String> expected = new ArrayList<>(onLoop(pipeline::names));
                expected.addFirst("foreign");
                var refusals = new CopyOnWriteArrayList<VelocityCompressedForward.Refusal>();
                Future<CompressedForwarder> install = null;
                synchronized (pipeline) {
                    if (on) {
                        install = client.proxySide()
                                .eventLoop()
                                .submit(() -> VelocityCompressedForward.install(
                                        pipeline,
                                        CompressEncoder.class,
                                        CompressDecoder.class,
                                        COMPRESSORS,
                                        refusals::add));
                        awaitBlockedOn(loop, pipeline);
                    }
                    pipeline.addFirst(foreign, "foreign", new ChannelInboundHandlerAdapter());
                }
                if (on) {
                    assertNull(install.get(5, TimeUnit.SECONDS));
                    assertEquals(List.of(VelocityCompressedForward.Refusal.EXECUTOR), refusals);
                }
                List<String> names = onLoop(pipeline::names);
                assertEquals(expected, names, "exactly the foreign handler more than before");
                seen.add(names);
                client.proxySide().close().sync();
            }
            assertEquals(seen.get(0), seen.get(1));
        } finally {
            foreign.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    @Test
    void handlerAddedOnAnotherExecutorWhileTheBackendLinkWaitsGetsNoKeepAsWithTheSwitchOff() throws Exception {
        var foreign = new DefaultEventExecutorGroup(1);
        try {
            List<List<String>> seen = new ArrayList<>();
            for (boolean on : new boolean[] {false, true}) {
                Pair client = client();
                var forwarder =
                        (CompressedForwarder) client.proxySide().pipeline().get(CompressedForwarder.NAME);
                assertNotNull(forwarder);
                Pair backend = backend(client.proxySide());
                ChannelPipeline pipeline = backend.proxySide().pipeline();
                Thread loop = onLoop(Thread::currentThread);
                List<String> expected = new ArrayList<>(onLoop(pipeline::names));
                expected.addFirst("foreign");
                Future<?> link = null;
                synchronized (pipeline) {
                    if (on) {
                        link = backend.proxySide()
                                .eventLoop()
                                .submit(() -> VelocityCompressedForward.link(
                                        forwarder,
                                        client.proxySide(),
                                        backend.proxySide(),
                                        CompressDecoder.class,
                                        COMPRESSORS));
                        awaitBlockedOn(loop, pipeline);
                    }
                    pipeline.addFirst(foreign, "foreign", new ChannelInboundHandlerAdapter());
                }
                if (on) {
                    link.get(5, TimeUnit.SECONDS);
                    assertEquals("backend-executor", onLoop(forwarder::linkStatus));
                }
                List<String> names = onLoop(pipeline::names);
                assertEquals(expected, names, "exactly the foreign handler more than before");
                seen.add(names);
                backend.proxySide().close().sync();
                client.proxySide().close().sync();
            }
            assertEquals(seen.get(0), seen.get(1));
        } finally {
            foreign.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    @Test
    void clientMovedToAnotherLoopAfterTheLinkIsSentAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        byte[] packet = packet(4000, 96);
        List<byte[]> sent = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = connect(true);
                receive(client);
                var written = new CountDownLatch(1);
                var held = new CountDownLatch(1);
                CompressedForwarder forwarder = onLoop(() -> {
                    var pipeline = client.proxySide().pipeline();
                    pipeline.addLast("written", new ChannelOutboundHandlerAdapter() {
                        @Override
                        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                            ctx.write(msg, promise);
                            written.countDown();
                        }
                    });
                    pipeline.addLast("cipher-encoder", new CipherEncoder());
                    var compressor = new SharedCompressor();
                    pipeline.addLast(
                            VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
                    pipeline.addLast(
                            CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD, true, compressor));
                    pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
                    pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
                    return on
                            ? VelocityCompressedForward.install(
                                    pipeline, CompressEncoder.class, CompressDecoder.class, COMPRESSORS)
                            : null;
                });
                if (on) assertNotNull(forwarder);
                Pair backend = connect(false);
                onLoop(() -> {
                    var pipeline = backend.proxySide().pipeline();
                    pipeline.addLast("frame-decoder", new FrameDecoder());
                    pipeline.addLast("read-timeout", new ChannelInboundHandlerAdapter());
                    pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                    pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
                    pipeline.addLast("handler", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            ByteBuf buf = (ByteBuf) msg;
                            try {
                                client.proxySide().writeAndFlush(buf.retain());
                                // The read holds its publish until the client's loop has run the write.
                                await(written);
                                held.countDown();
                            } finally {
                                buf.release();
                            }
                        }
                    });
                    if (on)
                        VelocityCompressedForward.link(
                                forwarder, client.proxySide(), backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                    return null;
                });
                if (on) assertEquals("linked", onLoop(forwarder::linkStatus));
                client.proxySide().deregister().sync();
                second.register(client.proxySide()).sync();
                assertNotSame(
                        backend.proxySide().eventLoop(), client.proxySide().eventLoop());
                backend.remote()
                        .writeAndFlush(Unpooled.wrappedBuffer(frame(body(packet, THRESHOLD))))
                        .sync();
                backend.proxySide().eventLoop().execute(() -> {
                    backend.proxySide().config().setAutoRead(true);
                    backend.proxySide().read();
                });
                await(held);
                assertPackets(List.of(packet), awaitFrames(1));
                synchronized (received) {
                    sent.add(received.toByteArray());
                }
                if (on)
                    assertEquals(
                            0L,
                            client.proxySide()
                                    .eventLoop()
                                    .submit(forwarder::forwarded)
                                    .get(5, TimeUnit.SECONDS));
                backend.proxySide().close().sync();
                client.proxySide().close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the frame the encoder compresses with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void backendDecoderPinnedToTheFirstLoopKeepsItsHandOffAfterAMoveAsWithTheSwitchOff() throws Exception {
        pinnedCodecMovedToAnotherLoop(true);
    }

    @Test
    void clientEncoderPinnedToTheFirstLoopKeepsItsHandOffAfterAMoveAsWithTheSwitchOff() throws Exception {
        pinnedCodecMovedToAnotherLoop(false);
    }

    /**
     * With the backend decoder, or else the client encoder, added on the channels' first loop as its own executor, moves
     * both channels to a second loop, holds the first and reads one eligible frame; compares what reaches the backend's
     * next handler and the client's head-side handler before and after the first loop resumes with the switch off.
     */
    private void pinnedCodecMovedToAnotherLoop(boolean pinDecoder) throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        EventLoop first = group.next();
        byte[] packet = packet(4000, 103);
        List<byte[]> sent = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = connect(true);
                receive(client);
                Channel toClient = client.proxySide();
                var headSide = new AtomicInteger();
                CompressedForwarder forwarder = onLoop(() -> {
                    var pipeline = toClient.pipeline();
                    pipeline.addLast("head-side", new ChannelOutboundHandlerAdapter() {
                        @Override
                        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                            headSide.incrementAndGet();
                            ctx.write(msg, promise);
                        }
                    });
                    pipeline.addLast("cipher-encoder", new CipherEncoder());
                    var compressor = new SharedCompressor();
                    pipeline.addLast(
                            VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
                    pipeline.addLast(
                            pinDecoder ? null : first,
                            CompressedForwarder.COMPRESSION_ENCODER,
                            new CompressEncoder(THRESHOLD, true, compressor));
                    pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
                    pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
                    return on
                            ? VelocityCompressedForward.install(
                                    pipeline, CompressEncoder.class, CompressDecoder.class, COMPRESSORS)
                            : null;
                });
                if (on) assertNotNull(forwarder);
                var relayed = new AtomicInteger();
                Pair backend = connect(false);
                onLoop(() -> {
                    var pipeline = backend.proxySide().pipeline();
                    pipeline.addLast("frame-decoder", new FrameDecoder());
                    pipeline.addLast("read-timeout", new ChannelInboundHandlerAdapter());
                    pipeline.addLast(
                            pinDecoder ? first : null,
                            VelocityCompressedForward.COMPRESSION_DECODER,
                            new CompressDecoder(THRESHOLD));
                    pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
                    pipeline.addLast("handler", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ByteBuf buf = (ByteBuf) msg;
                            try {
                                relayed.incrementAndGet();
                                toClient.writeAndFlush(buf.retain());
                            } finally {
                                buf.release();
                            }
                        }
                    });
                    if (on)
                        VelocityCompressedForward.link(
                                forwarder, toClient, backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                    return null;
                });
                if (on) assertEquals("linked", onLoop(forwarder::linkStatus));
                for (Channel channel : List.of(toClient, backend.proxySide())) {
                    channel.deregister().sync();
                    second.register(channel).sync();
                }
                assertNotSame(first, toClient.eventLoop());
                assertSame(toClient.eventLoop(), backend.proxySide().eventLoop());
                backend.remote()
                        .writeAndFlush(Unpooled.wrappedBuffer(frame(body(packet, THRESHOLD))))
                        .sync();
                var holding = new CountDownLatch(1);
                var resume = new CountDownLatch(1);
                first.execute(() -> {
                    holding.countDown();
                    await(resume);
                });
                try {
                    await(holding);
                    within(backend.proxySide(), () -> {
                        backend.proxySide().config().setAutoRead(true);
                        backend.proxySide().read();
                        return null;
                    });
                    assertEquals(
                            List.of(pinDecoder ? 0 : 1, 0),
                            within(toClient, () -> List.of(relayed.get(), headSide.get())),
                            "delivered before the first loop resumes, switch " + (on ? "on" : "off"));
                } finally {
                    resume.countDown();
                }
                assertPackets(List.of(packet), awaitFrames(1));
                synchronized (received) {
                    sent.add(received.toByteArray());
                }
                assertEquals(List.of(1, 1), within(toClient, () -> List.of(relayed.get(), headSide.get())));
                if (on) {
                    assertEquals(0L, within(toClient, forwarder::forwarded));
                    assertEquals("on", within(toClient, forwarder::status));
                    assertNull(within(toClient, forwarder::markFrame), "every private frame is taken or released");
                }
                backend.proxySide().close().sync();
                toClient.close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the frame the encoder compresses with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    /** A client pipeline whose compression encoder is added with {@code pinned} as its own executor. */
    private static CompressedForwarder pinnedEncoderClient(
            ChannelPipeline pipeline, EventLoop pinned, ChannelOutboundHandlerAdapter tailSide, boolean forward) {
        pipeline.addLast("cipher-encoder", new CipherEncoder());
        var compressor = new SharedCompressor();
        pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
        pipeline.addLast(
                pinned, CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD, true, compressor));
        pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
        pipeline.addLast(pinned, "minecraft-encoder", tailSide);
        return forward
                ? VelocityCompressedForward.install(pipeline, CompressEncoder.class, CompressDecoder.class, COMPRESSORS)
                : null;
    }

    private static void moveTo(EventLoopGroup loop, Channel... channels) throws Exception {
        for (Channel channel : channels) {
            channel.deregister().sync();
            loop.register(channel).sync();
        }
    }

    @Test
    void writesFromThePinnedEncodersLoopKeepTheirOrderAfterAMoveAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        EventLoop first = group.next();
        byte[] direct = packet(600, 105);
        byte[] queued = packet(400, 106);
        List<byte[]> sent = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = connect(true);
                receive(client);
                Channel toClient = client.proxySide();
                CompressedForwarder forwarder = onLoop(
                        () -> pinnedEncoderClient(toClient.pipeline(), first, new ChannelOutboundHandlerAdapter(), on));
                if (on) assertNotNull(forwarder);
                moveTo(second, toClient);
                assertNotSame(first, toClient.eventLoop());
                first.submit(() -> {
                            // The second packet's write is queued on the client's loop before the first is written.
                            toClient.eventLoop().execute(() -> toClient.writeAndFlush(Unpooled.wrappedBuffer(queued)));
                            toClient.writeAndFlush(Unpooled.wrappedBuffer(direct));
                            return null;
                        })
                        .get(5, TimeUnit.SECONDS);
                assertPackets(List.of(direct, queued), awaitFrames(2));
                synchronized (received) {
                    sent.add(received.toByteArray());
                }
                if (on) assertEquals("on", within(toClient, forwarder::status));
                toClient.close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the frames the encoder compresses with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void publishedBufferWrittenOnThePinnedEncodersLoopDuringItsPublishIsSentAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        EventLoop first = group.next();
        byte[] packet = packet(4000, 107);
        List<byte[]> sent = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = connect(true);
                receive(client);
                Channel toClient = client.proxySide();
                var written = new CountDownLatch(1);
                CompressedForwarder forwarder = onLoop(() -> pinnedEncoderClient(
                        toClient.pipeline(),
                        first,
                        new ChannelOutboundHandlerAdapter() {
                            @Override
                            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                                ctx.write(msg, promise);
                                written.countDown();
                            }
                        },
                        on));
                if (on) assertNotNull(forwarder);
                Pair backend = connect(false);
                onLoop(() -> {
                    var pipeline = backend.proxySide().pipeline();
                    pipeline.addLast("frame-decoder", new FrameDecoder());
                    pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                    pipeline.addLast("handler", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ByteBuf buf = (ByteBuf) msg;
                            try {
                                toClient.writeAndFlush(buf.retain());
                                // The publish lasts until the encoder's executor has run the write.
                                await(written);
                            } finally {
                                buf.release();
                            }
                        }
                    });
                    if (on)
                        VelocityCompressedForward.link(
                                forwarder, toClient, backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                    return null;
                });
                if (on) assertEquals("linked", onLoop(forwarder::linkStatus));
                moveTo(second, toClient, backend.proxySide());
                assertSame(toClient.eventLoop(), backend.proxySide().eventLoop());
                serve(backend, packet);
                assertPackets(List.of(packet), awaitFrames(1));
                synchronized (received) {
                    sent.add(received.toByteArray());
                }
                if (on) {
                    assertEquals(0L, within(toClient, forwarder::forwarded));
                    assertEquals("on", within(toClient, forwarder::status));
                    assertNull(within(toClient, forwarder::markFrame), "every private frame is taken or released");
                }
                backend.proxySide().close().sync();
                toClient.close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the frame the encoder compresses with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void readThroughHandlersPinnedWithTheBackendDecoderKeepsItsOrderAfterAMoveAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        EventLoop first = group.next();
        byte[] packet = packet(4000, 108);
        List<List<String>> orders = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                Pair client = client(on);
                Channel toClient = client.proxySide();
                var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                if (on) assertNotNull(forwarder);
                var events = new CopyOnWriteArrayList<String>();
                Pair backend = connect(false);
                onLoop(() -> {
                    var pipeline = backend.proxySide().pipeline();
                    pipeline.addLast(first, "frame-decoder", new FrameDecoder());
                    pipeline.addLast(
                            first, VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                    pipeline.addLast(first, "handler", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ((ByteBuf) msg).release();
                            events.add("decoded");
                        }
                    });
                    if (on)
                        VelocityCompressedForward.link(
                                forwarder, toClient, backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                    return null;
                });
                if (on) assertEquals("linked", onLoop(forwarder::linkStatus));
                moveTo(second, toClient, backend.proxySide());
                assertSame(toClient.eventLoop(), backend.proxySide().eventLoop());
                backend.remote()
                        .writeAndFlush(Unpooled.wrappedBuffer(frame(body(packet, THRESHOLD))))
                        .sync();
                var holding = new CountDownLatch(1);
                var resume = new CountDownLatch(1);
                first.execute(() -> {
                    holding.countDown();
                    await(resume);
                });
                try {
                    await(holding);
                    within(backend.proxySide(), () -> {
                        backend.proxySide().config().setAutoRead(true);
                        backend.proxySide().read();
                        return null;
                    });
                    first.execute(() -> events.add("queued"));
                } finally {
                    resume.countDown();
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (events.size() < 2) {
                    if (System.nanoTime() > deadline) fail("events: " + events);
                    Thread.onSpinWait();
                }
                orders.add(List.copyOf(events));
                if (on) assertEquals(0L, within(toClient, forwarder::forwarded));
                backend.proxySide().close().sync();
                toClient.close().sync();
            }
            assertEquals(List.of("decoded", "queued"), orders.get(0), "the switched-off order on the first loop");
            assertEquals(orders.get(0), orders.get(1));
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void clientDecoderRemovedOrReplacedFromAnotherThreadBeforeAWriteIsSentAsWithTheSwitchOff() throws Exception {
        byte[] packet = packet(4000, 104);
        for (boolean replace : new boolean[] {false, true}) {
            List<byte[]> sent = new ArrayList<>();
            List<Boolean> succeeded = new ArrayList<>();
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = client(on);
                Channel toClient = client.proxySide();
                ChannelPipeline clientPipeline = toClient.pipeline();
                var forwarder = (CompressedForwarder) clientPipeline.get(CompressedForwarder.NAME);
                if (on) assertNotNull(forwarder);
                ChannelHandlerContext old =
                        onLoop(() -> clientPipeline.context(VelocityCompressedForward.COMPRESSION_DECODER));
                var pending = new CopyOnWriteArrayList<Boolean>();
                var writes = new CopyOnWriteArrayList<ChannelFuture>();
                Pair backend = connect(false);
                onLoop(() -> {
                    var pipeline = backend.proxySide().pipeline();
                    pipeline.addLast("frame-decoder", new FrameDecoder());
                    pipeline.addLast("remover", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            ctx.pipeline().remove(this);
                            var other = new Thread(() -> {
                                if (replace)
                                    clientPipeline.replace(
                                            VelocityCompressedForward.COMPRESSION_DECODER,
                                            VelocityCompressedForward.COMPRESSION_DECODER,
                                            new CompressDecoder(THRESHOLD));
                                else clientPipeline.remove(VelocityCompressedForward.COMPRESSION_DECODER);
                            });
                            other.start();
                            other.join();
                            // Off the loop, netty unlinks the context at once and queues its removal callback.
                            pending.add(!old.isRemoved() && clientPipeline.context(old.handler()) == null);
                            ctx.fireChannelRead(msg);
                        }
                    });
                    pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                    pipeline.addLast("handler", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ByteBuf buf = (ByteBuf) msg;
                            try {
                                writes.add(toClient.writeAndFlush(buf.retain()));
                            } finally {
                                buf.release();
                            }
                        }
                    });
                    if (on)
                        VelocityCompressedForward.link(
                                forwarder, toClient, backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                    return null;
                });
                if (on) assertEquals("linked", onLoop(forwarder::linkStatus));
                serve(backend, packet);
                assertPackets(List.of(packet), awaitFrames(1));
                synchronized (received) {
                    sent.add(received.toByteArray());
                }
                assertEquals(List.of(true), pending, "the read runs with the removal callback queued");
                assertEquals(1, writes.size());
                assertTrue(writes.getFirst().await(5, TimeUnit.SECONDS));
                succeeded.add(writes.getFirst().isSuccess());
                if (on) {
                    assertEquals(0L, onLoop(forwarder::forwarded));
                    assertEquals("compression-removed", onLoop(forwarder::status));
                }
                backend.proxySide().close().sync();
                toClient.close().sync();
            }
            String variant = replace ? "replaced" : "removed";
            assertEquals(List.of(true, true), succeeded, variant);
            assertArrayEquals(sent.get(0), sent.get(1), variant + ": the bytes the encoder sends with the switch off");
        }
    }

    /** Waits until {@code thread} is blocked entering {@code monitor}'s monitor. */
    private static void awaitBlockedOn(Thread thread, Object monitor) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            ThreadInfo info = threads.getThreadInfo(thread.threadId());
            LockInfo lock = info == null ? null : info.getLockInfo();
            if (info != null
                    && info.getThreadState() == Thread.State.BLOCKED
                    && lock != null
                    && lock.getIdentityHashCode() == System.identityHashCode(monitor)
                    && lock.getClassName().equals(monitor.getClass().getName())) return;
            if (System.nanoTime() > deadline) fail("not blocked on the pipeline: " + info);
            Thread.onSpinWait();
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
