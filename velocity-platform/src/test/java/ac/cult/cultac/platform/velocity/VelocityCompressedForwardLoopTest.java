package ac.cult.cultac.platform.velocity;

import static ac.cult.cultac.platform.velocity.CompressedFrames.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import ac.cult.cultac.platform.velocity.VelocityBackendBurstDeliveryTest.BackendConnection;
import ac.cult.cultac.platform.velocity.VelocityBackendBurstDeliveryTest.BackendServer;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
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
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
                if (on) {
                    assertNull(
                            toClient.pipeline().get(CompressedForwarder.NAME),
                            "the forwarder leaves at deregistration");
                    assertNull(
                            within(
                                    oldBackend.proxySide(),
                                    () -> oldBackend.proxySide().pipeline().get(CompressedKeep.NAME)),
                            "its keep goes with it");
                }
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
                    assertEquals("none", within(toClient, forwarder::linkStatus));
                    assertNull(
                            within(
                                    nextBackend.proxySide(),
                                    () -> nextBackend.proxySide().pipeline().get(CompressedKeep.NAME)),
                            "a client without a forwarder links nothing");
                }
                nextBackend.proxySide().eventLoop().execute(() -> {
                    nextBackend.proxySide().config().setAutoRead(true);
                    nextBackend.proxySide().read();
                });
                var frames = awaitFrames(2);
                assertPackets(List.of(next, old), frames);
                sent.add(concat(frames.get(0).body(), frames.get(1).body()));
                if (on) {
                    assertEquals(0L, within(toClient, forwarder::forwarded));
                    assertEquals("removed", within(toClient, forwarder::status));
                    assertNull(within(toClient, () -> forwarder.markBuf), "no mark outlives the reads");
                    assertNull(within(toClient, forwarder::markFrame), "every private frame is taken or released");
                }
                oldBackend.proxySide().close().sync();
                nextBackend.proxySide().close().sync();
                toClient.close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the frames the encoder compresses with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void clientMovedToAnotherLoopHasNoForwarderThereAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        byte[] first = packet(4000, 99);
        byte[] after = packet(3000, 100);
        List<byte[]> sent = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = client(on);
                Channel toClient = client.proxySide();
                var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                Pair firstBackend = backend(group, relay(toClient));
                if (on) {
                    within(toClient, () -> {
                        VelocityCompressedForward.link(
                                forwarder, toClient, firstBackend.proxySide(), CompressDecoder.class, COMPRESSORS);
                        return null;
                    });
                }
                serve(firstBackend, first);
                assertPackets(List.of(first), awaitFrames(1));
                if (on) assertEquals(1L, within(toClient, forwarder::forwarded));

                List<String> before = within(toClient, () -> toClient.pipeline().names());
                moveTo(second, toClient);
                Pair nextBackend = backend(second, relay(toClient));
                assertSame(toClient.eventLoop(), nextBackend.proxySide().eventLoop());
                if (on) {
                    within(toClient, () -> {
                        VelocityCompressedForward.link(
                                forwarder, toClient, nextBackend.proxySide(), CompressDecoder.class, COMPRESSORS);
                        return null;
                    });
                    assertEquals("none", within(toClient, forwarder::linkStatus));
                    assertEquals("removed", within(toClient, forwarder::status));
                    assertNull(within(
                            nextBackend.proxySide(),
                            () -> nextBackend.proxySide().pipeline().get(CompressedKeep.NAME)));
                }
                List<String> names = new ArrayList<>(before);
                names.remove(CompressedForwarder.NAME);
                assertEquals(names, within(toClient, () -> toClient.pipeline().names()), "the switched-off handlers");
                serve(nextBackend, after);
                var frames = awaitFrames(2);
                assertPackets(List.of(first, after), frames);
                sent.add(frames.get(1).body());
                if (on) assertEquals(1L, within(toClient, forwarder::forwarded), "nothing forwarded after the move");
                firstBackend.proxySide().close().sync();
                nextBackend.proxySide().close().sync();
                toClient.close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the frame the encoder compresses with the switch off");
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
                        remover.join(5000);
                        assertFalse(remover.isAlive(), "the remover thread finished");
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
     * next handler and the client's head-side handler before and after the first loop resumes with the switch off, and
     * expects no keep, or else no forwarder.
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
                var refusals = new CopyOnWriteArrayList<VelocityCompressedForward.Refusal>();
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
                                    pipeline, CompressEncoder.class, CompressDecoder.class, COMPRESSORS, refusals::add)
                            : null;
                });
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
                    if (forwarder != null)
                        VelocityCompressedForward.link(
                                forwarder, toClient, backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                    return null;
                });
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
                if (on && pinDecoder) {
                    assertNotNull(forwarder);
                    assertEquals("backend-executor", within(toClient, forwarder::linkStatus));
                    assertEquals(0L, within(toClient, forwarder::forwarded));
                } else if (on) {
                    assertNull(forwarder);
                    assertEquals(List.of(VelocityCompressedForward.Refusal.EXECUTOR), refusals);
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
    void backendDecoderPinnedAndRemovedAfterAMoveLeavesNoHandOffAsWithTheSwitchOff() throws Exception {
        pinnedCodecRemovedAfterAMove(true);
    }

    @Test
    void clientEncoderPinnedAndRemovedAfterAMoveLeavesNoHandOffAsWithTheSwitchOff() throws Exception {
        pinnedCodecRemovedAfterAMove(false);
    }

    /**
     * With the backend decoder, or else the client encoder, added on the channels' first loop as its own executor, moves
     * both channels to a second loop and removes that codec there, holds the first loop and reads one frame, which the
     * relay writes and flushes at read completion; compares what the relay and the client's head-side handler see before
     * the first loop resumes, and the bytes sent, with the switch off.
     */
    private void pinnedCodecRemovedAfterAMove(boolean pinDecoder) throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        EventLoop first = group.next();
        byte[] packet = packet(4000, 109);
        List<byte[]> sent = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = connect(true);
                receive(client);
                Channel toClient = client.proxySide();
                var headSide = new CopyOnWriteArrayList<String>();
                var refusals = new CopyOnWriteArrayList<VelocityCompressedForward.Refusal>();
                CompressedForwarder forwarder = onLoop(() -> {
                    var pipeline = toClient.pipeline();
                    pipeline.addLast("head-side", new ChannelOutboundHandlerAdapter() {
                        @Override
                        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                            headSide.add("write");
                            ctx.write(msg, promise);
                        }

                        @Override
                        public void flush(ChannelHandlerContext ctx) {
                            headSide.add("flush");
                            ctx.flush();
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
                                    pipeline, CompressEncoder.class, CompressDecoder.class, COMPRESSORS, refusals::add)
                            : null;
                });
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
                                toClient.write(buf.retain());
                            } finally {
                                buf.release();
                            }
                        }

                        @Override
                        public void channelReadComplete(ChannelHandlerContext ctx) {
                            toClient.flush();
                        }
                    });
                    if (forwarder != null)
                        VelocityCompressedForward.link(
                                forwarder, toClient, backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                    return null;
                });
                moveTo(second, toClient, backend.proxySide());
                assertSame(toClient.eventLoop(), backend.proxySide().eventLoop());
                Channel pinned = pinDecoder ? backend.proxySide() : toClient;
                within(
                        pinned,
                        () -> pinned.pipeline()
                                .remove(
                                        pinDecoder
                                                ? VelocityCompressedForward.COMPRESSION_DECODER
                                                : CompressedForwarder.COMPRESSION_ENCODER));
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
                            List.of(1, List.of("write", "flush")),
                            within(toClient, () -> List.of(relayed.get(), List.copyOf(headSide))),
                            "delivered before the first loop resumes, switch " + (on ? "on" : "off"));
                } finally {
                    resume.countDown();
                }
                // The client's peer reads on the first loop, after the flush that queued its read.
                within(client.remote(), () -> null);
                synchronized (received) {
                    sent.add(received.toByteArray());
                }
                if (on && pinDecoder) {
                    assertNotNull(forwarder);
                    assertEquals("backend-executor", within(toClient, forwarder::linkStatus));
                } else if (on) {
                    assertNull(forwarder);
                    assertEquals(List.of(VelocityCompressedForward.Refusal.EXECUTOR), refusals);
                }
                backend.proxySide().close().sync();
                toClient.close().sync();
            }
            assertTrue(sent.get(0).length > 0, "the switched-off run sends the packet");
            assertArrayEquals(sent.get(0), sent.get(1), "the bytes sent with the switch off");
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
                if (on) assertNull(forwarder, "an encoder added with an executor of its own gets no forwarder");
                toClient.close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the frames the encoder compresses with the switch off");
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
                if (on) {
                    assertEquals(0L, within(toClient, forwarder::forwarded));
                    assertEquals("backend-executor", within(toClient, forwarder::linkStatus));
                }
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
        byte[] after = packet(3000, 105);
        for (boolean replace : new boolean[] {false, true}) {
            List<byte[]> sent = new ArrayList<>();
            List<String> outcomes = new ArrayList<>();
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
                            other.join(5000);
                            assertFalse(other.isAlive(), "the remover thread finished");
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
                assertEquals(List.of(true), pending, "the read runs with the removal callback queued");
                if (on) {
                    assertEquals(0L, onLoop(forwarder::forwarded));
                    assertEquals("on", onLoop(forwarder::status), "the frame written before the callback");
                    assertEquals(1L, onLoop(forwarder::decoderUnlinked), "the write counted outside the hit rate");
                }
                assertTrue(onLoop(old::isRemoved), "the removal callback has run");
                serve(backend, after);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (writes.size() < 2) {
                    if (System.nanoTime() > deadline) fail("writes: " + writes.size());
                    Thread.onSpinWait();
                }
                for (ChannelFuture write : writes) {
                    assertTrue(write.await(5, TimeUnit.SECONDS));
                    Throwable cause = write.cause();
                    outcomes.add(cause == null ? "sent" : cause.getClass().getName() + ": " + cause.getMessage());
                }
                // The removed decoder closes the compressor it shares with the encoder.
                assertPackets(List.of(packet), awaitFrames(1));
                synchronized (received) {
                    sent.add(received.toByteArray());
                }
                if (on) {
                    assertEquals(0L, onLoop(forwarder::forwarded));
                    assertEquals("compression-removed", onLoop(forwarder::status));
                }
                backend.proxySide().close().sync();
                toClient.close().sync();
            }
            String variant = replace ? "replaced" : "removed";
            assertEquals("sent", outcomes.get(0), variant);
            assertEquals(
                    outcomes.subList(0, 2), outcomes.subList(2, 4), variant + ": the outcomes with the switch off");
            assertArrayEquals(sent.get(0), sent.get(1), variant + ": the bytes the encoder sends with the switch off");
        }
    }

    /**
     * A handler inserted before the client decoder from another thread, held after the store that links the
     * decoder's head-side neighbour to the new context and before the decoder's own link, as netty's {@code addBefore}
     * writes them under the pipeline's monitor, until the read that writes the published buffer has returned.
     */
    @Test
    void handlerHalfInsertedBeforeTheClientDecoderDuringAWriteLeavesTheForwarderOn() throws Exception {
        byte[] packet = packet(4000, 106);
        byte[] next = packet(3000, 107);
        Class<?> context = Class.forName("io.netty.channel.AbstractChannelHandlerContext");
        VarHandle previous =
                MethodHandles.privateLookupIn(context, MethodHandles.lookup()).findVarHandle(context, "prev", context);
        List<byte[]> sent = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            synchronized (received) {
                received.reset();
            }
            Pair client = client(on);
            Channel toClient = client.proxySide();
            ChannelPipeline clientPipeline = toClient.pipeline();
            var forwarder = (CompressedForwarder) clientPipeline.get(CompressedForwarder.NAME);
            if (on) assertNotNull(forwarder);
            ChannelHandlerContext decoder =
                    onLoop(() -> clientPipeline.context(VelocityCompressedForward.COMPRESSION_DECODER));
            var errors = new CopyOnWriteArrayList<Throwable>();
            var writes = new CopyOnWriteArrayList<ChannelFuture>();
            var inserter = new AtomicReference<Thread>();
            Pair backend = connect(false);
            onLoop(() -> {
                var pipeline = backend.proxySide().pipeline();
                pipeline.addLast("frame-decoder", new FrameDecoder());
                pipeline.addLast("inserter", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        ctx.pipeline().remove(this);
                        var halfDone = new CountDownLatch(1);
                        var readDone = new CountDownLatch(1);
                        var other = new Thread(() -> {
                            synchronized (clientPipeline) {
                                clientPipeline.addBefore(
                                        VelocityCompressedForward.COMPRESSION_DECODER,
                                        "inserted",
                                        new ChannelInboundHandlerAdapter());
                                ChannelHandlerContext inserted = clientPipeline.context("inserted");
                                Object head = previous.getVolatile(inserted);
                                previous.setVolatile(decoder, head);
                                halfDone.countDown();
                                try {
                                    if (!readDone.await(5, TimeUnit.SECONDS))
                                        errors.add(new AssertionError("the write waited at the pipeline's monitor"));
                                } catch (Throwable failure) {
                                    errors.add(failure);
                                } finally {
                                    previous.setVolatile(decoder, inserted);
                                }
                            }
                        });
                        inserter.set(other);
                        other.start();
                        assertTrue(halfDone.await(5, TimeUnit.SECONDS), "the insert is half done");
                        try {
                            ctx.fireChannelRead(msg);
                        } finally {
                            readDone.countDown();
                        }
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
            Thread other = inserter.get();
            assertNotNull(other);
            other.join(10_000);
            assertFalse(other.isAlive(), "the inserting thread finished");
            assertEquals(List.of(), errors, "the write did not wait at the pipeline's monitor");
            synchronized (received) {
                sent.add(received.toByteArray());
            }
            if (on) {
                assertEquals("on", onLoop(forwarder::status));
                assertEquals(0L, onLoop(forwarder::forwarded), "the half-linked frame takes the encoder");
            }
            serve(backend, next);
            assertPackets(List.of(packet, next), awaitFrames(2));
            if (on) {
                assertEquals("on", onLoop(forwarder::status));
                assertEquals(1L, onLoop(forwarder::forwarded), "the frame after the insert forwarded");
            }
            assertEquals(2, writes.size());
            for (ChannelFuture write : writes) {
                assertTrue(write.await(5, TimeUnit.SECONDS));
                assertTrue(write.isSuccess());
            }
            assertEquals(List.of("inserted", VelocityCompressedForward.COMPRESSION_DECODER), onLoop(() -> {
                List<String> names = clientPipeline.names();
                int at = names.indexOf("inserted");
                return names.subList(at, at + 2);
            }));
            backend.proxySide().close().sync();
            toClient.close().sync();
        }
        assertArrayEquals(sent.get(0), sent.get(1), "the half-linked frame's bytes as the encoder sends them");
    }

    /**
     * A thread that removes the client decoder and then, holding the client pipeline's monitor, waits for a task on
     * the client's loop while a backend read on that loop writes the published buffer.
     */
    @Test
    void monitorHolderWaitingForTheLoopDuringAWriteGetsItsAnswerAsWithTheSwitchOff() throws Exception {
        byte[] packet = packet(4000, 108);
        List<byte[]> sent = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            synchronized (received) {
                received.reset();
            }
            Pair client = client(on);
            Channel toClient = client.proxySide();
            ChannelPipeline clientPipeline = toClient.pipeline();
            var forwarder = (CompressedForwarder) clientPipeline.get(CompressedForwarder.NAME);
            if (on) assertNotNull(forwarder);
            var answers = new CopyOnWriteArrayList<Boolean>();
            var writes = new CopyOnWriteArrayList<ChannelFuture>();
            var holder = new AtomicReference<Thread>();
            Pair backend = connect(false);
            onLoop(() -> {
                var pipeline = backend.proxySide().pipeline();
                pipeline.addLast("frame-decoder", new FrameDecoder());
                pipeline.addLast("holder", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        ctx.pipeline().remove(this);
                        var holding = new CountDownLatch(1);
                        var other = new Thread(() -> {
                            synchronized (clientPipeline) {
                                clientPipeline.remove(VelocityCompressedForward.COMPRESSION_DECODER);
                                Future<Boolean> task = toClient.eventLoop().submit(() -> true);
                                holding.countDown();
                                try {
                                    answers.add(task.get(5, TimeUnit.SECONDS));
                                } catch (Exception timedOut) {
                                    answers.add(false);
                                }
                            }
                        });
                        holder.set(other);
                        other.start();
                        assertTrue(holding.await(5, TimeUnit.SECONDS), "the holder waits for the loop");
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
            Thread other = holder.get();
            assertNotNull(other);
            other.join(10_000);
            assertFalse(other.isAlive(), "the holder finished");
            assertEquals(List.of(true), answers, "the holder's task ran on the loop");
            synchronized (received) {
                sent.add(received.toByteArray());
            }
            assertEquals(1, writes.size());
            assertTrue(writes.getFirst().await(5, TimeUnit.SECONDS));
            assertTrue(writes.getFirst().isSuccess());
            if (on) {
                assertEquals(0L, onLoop(forwarder::forwarded));
                assertEquals(1L, onLoop(forwarder::decoderUnlinked), "the published write reached the link check");
            }
            backend.proxySide().close().sync();
            toClient.close().sync();
        }
        assertArrayEquals(sent.get(0), sent.get(1), "the bytes the encoder sends with the switch off");
    }

    /**
     * A thread that, holding the backend pipeline's monitor, inserts a handler between the keep and the decoder, lets
     * a backend read on the shared loop reach the keep, which refuses the frame, and once that read has returned
     * submits a task to the loop and waits for it.
     */
    @Test
    void backendMonitorHolderWaitingForTheLoopDuringARefusedReadGetsItsAnswerAsWithTheSwitchOff() throws Exception {
        byte[] packet = packet(4000, 109);
        List<byte[]> sent = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            synchronized (received) {
                received.reset();
            }
            Pair client = client(on);
            Channel toClient = client.proxySide();
            var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
            var answers = new CopyOnWriteArrayList<Boolean>();
            var holder = new AtomicReference<Thread>();
            var ended = new CountDownLatch(1);
            Pair backend = connect(false);
            ChannelPipeline backendPipeline = backend.proxySide().pipeline();
            onLoop(() -> {
                backendPipeline.addLast("frame-decoder", new FrameDecoder());
                backendPipeline.addLast("holder", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        ctx.pipeline().remove(this);
                        var holding = new CountDownLatch(1);
                        var other = new Thread(() -> {
                            synchronized (backendPipeline) {
                                backendPipeline.addBefore(
                                        VelocityCompressedForward.COMPRESSION_DECODER,
                                        "inserted",
                                        new ChannelInboundHandlerAdapter());
                                holding.countDown();
                                try {
                                    assertTrue(ended.await(5, TimeUnit.SECONDS), "the read has ended the link");
                                    Future<Boolean> task = toClient.eventLoop().submit(() -> true);
                                    answers.add(task.get(5, TimeUnit.SECONDS));
                                } catch (Exception timedOut) {
                                    answers.add(false);
                                }
                            }
                        });
                        holder.set(other);
                        other.start();
                        assertTrue(holding.await(5, TimeUnit.SECONDS), "the holder holds the monitor");
                        ctx.fireChannelRead(msg);
                        ended.countDown();
                    }
                });
                backendPipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                backendPipeline.addLast("handler", relay(toClient));
                if (on)
                    VelocityCompressedForward.link(
                            forwarder, toClient, backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                return null;
            });
            if (on) assertEquals("linked", onLoop(forwarder::linkStatus));
            serve(backend, packet);
            assertPackets(List.of(packet), awaitFrames(1));
            Thread other = holder.get();
            assertNotNull(other);
            other.join(10_000);
            assertFalse(other.isAlive(), "the holder finished");
            assertEquals(List.of(true), answers, "the holder's task ran on the loop");
            synchronized (received) {
                sent.add(received.toByteArray());
            }
            if (on) {
                assertEquals(0L, onLoop(forwarder::forwarded));
                assertEquals("backend-adjacency", onLoop(forwarder::linkStatus));
                assertNotNull(onLoop(() -> backendPipeline.get(CompressedKeep.NAME)), "the keep stays until an unlink");
                onLoop(() -> {
                    VelocityCompressedForward.uninstall(toClient);
                    return null;
                });
                assertNull(onLoop(() -> backendPipeline.get(CompressedKeep.NAME)), "the forwarder's removal takes it");
            }
            backend.proxySide().close().sync();
            toClient.close().sync();
        }
        assertArrayEquals(sent.get(0), sent.get(1), "the bytes the encoder sends with the switch off");
    }

    /**
     * A thread that holds the backend pipeline's monitor while a published write finds a handler inserted between the
     * client encoder and the forwarder, which turns the forwarder off, and once that write has returned submits a task
     * to the shared loop and waits for it.
     */
    @Test
    void backendMonitorHolderWaitingForTheLoopDuringAWriteThatEndsTheLinkGetsItsAnswerAsWithTheSwitchOff()
            throws Exception {
        byte[] packet = packet(4000, 110);
        List<byte[]> sent = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            synchronized (received) {
                received.reset();
            }
            Pair client = client(on);
            Channel toClient = client.proxySide();
            ChannelPipeline clientPipeline = toClient.pipeline();
            var forwarder = (CompressedForwarder) clientPipeline.get(CompressedForwarder.NAME);
            var answers = new CopyOnWriteArrayList<Boolean>();
            var writes = new CopyOnWriteArrayList<ChannelFuture>();
            var holder = new AtomicReference<Thread>();
            var ended = new CountDownLatch(1);
            Pair backend = connect(false);
            ChannelPipeline backendPipeline = backend.proxySide().pipeline();
            onLoop(() -> {
                backendPipeline.addLast("frame-decoder", new FrameDecoder());
                backendPipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                backendPipeline.addLast("handler", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        ByteBuf buf = (ByteBuf) msg;
                        try {
                            var holding = new CountDownLatch(1);
                            var other = new Thread(() -> {
                                synchronized (backendPipeline) {
                                    holding.countDown();
                                    try {
                                        assertTrue(ended.await(5, TimeUnit.SECONDS), "the write has ended the link");
                                        Future<Boolean> task =
                                                toClient.eventLoop().submit(() -> true);
                                        answers.add(task.get(5, TimeUnit.SECONDS));
                                    } catch (Exception timedOut) {
                                        answers.add(false);
                                    }
                                }
                            });
                            holder.set(other);
                            other.start();
                            assertTrue(holding.await(5, TimeUnit.SECONDS), "the holder holds the monitor");
                            clientPipeline.addAfter(
                                    CompressedForwarder.COMPRESSION_ENCODER,
                                    "between",
                                    new ChannelOutboundHandlerAdapter());
                            writes.add(toClient.writeAndFlush(buf.retain()));
                            ended.countDown();
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
            Thread other = holder.get();
            assertNotNull(other);
            other.join(10_000);
            assertFalse(other.isAlive(), "the holder finished");
            assertEquals(List.of(true), answers, "the holder's task ran on the loop");
            synchronized (received) {
                sent.add(received.toByteArray());
            }
            assertEquals(1, writes.size());
            assertTrue(writes.getFirst().await(5, TimeUnit.SECONDS));
            assertTrue(writes.getFirst().isSuccess());
            if (on) {
                assertEquals(0L, onLoop(forwarder::forwarded));
                assertEquals("adjacency", onLoop(forwarder::status));
                assertNotNull(onLoop(() -> backendPipeline.get(CompressedKeep.NAME)), "the keep stays until an unlink");
                onLoop(() -> {
                    VelocityCompressedForward.uninstall(toClient);
                    return null;
                });
                assertNull(onLoop(() -> backendPipeline.get(CompressedKeep.NAME)), "the forwarder's removal takes it");
            }
            backend.proxySide().close().sync();
            toClient.close().sync();
        }
        assertArrayEquals(sent.get(0), sent.get(1), "the bytes the encoder sends with the switch off");
    }

    /**
     * A handler inserted between the keep and the decoder that removes itself at the first of two frames of one read:
     * the keep refuses the first, and its link has ended when the second arrives with the decoder adjacent again.
     */
    @Test
    void keepWhoseLinkEndedPassesTheNextFrameOfTheReadOnAsWithTheSwitchOff() throws Exception {
        byte[] first = packet(3000, 111);
        byte[] second = packet(3000, 112);
        List<byte[]> sent = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            synchronized (received) {
                received.reset();
            }
            Pair client = client(on);
            var forwarder = (CompressedForwarder) client.proxySide().pipeline().get(CompressedForwarder.NAME);
            Pair backend = backend(client.proxySide());
            ChannelPipeline backendPipeline = backend.proxySide().pipeline();
            onLoop(() -> {
                if (on)
                    VelocityCompressedForward.link(
                            forwarder, client.proxySide(), backend.proxySide(), CompressDecoder.class, COMPRESSORS);
                backendPipeline.addBefore(
                        VelocityCompressedForward.COMPRESSION_DECODER, "between", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ctx.pipeline().remove(this);
                                ctx.fireChannelRead(msg);
                            }
                        });
                return null;
            });
            if (on) {
                assertEquals("linked", onLoop(forwarder::linkStatus));
                List<String> names = onLoop(backendPipeline::names);
                int keep = names.indexOf(CompressedKeep.NAME);
                assertEquals(
                        List.of(CompressedKeep.NAME, "between", VelocityCompressedForward.COMPRESSION_DECODER),
                        names.subList(keep, keep + 3));
            }
            backend.remote()
                    .writeAndFlush(Unpooled.wrappedBuffer(
                            concat(frame(body(first, THRESHOLD)), frame(body(second, THRESHOLD)))))
                    .sync();
            backend.proxySide().eventLoop().execute(() -> {
                backend.proxySide().config().setAutoRead(true);
                backend.proxySide().read();
            });
            assertPackets(List.of(first, second), awaitFrames(2));
            synchronized (received) {
                sent.add(received.toByteArray());
            }
            if (on) {
                assertEquals(0L, onLoop(forwarder::forwarded));
                assertEquals("on", onLoop(forwarder::status));
                assertEquals("backend-adjacency", onLoop(forwarder::linkStatus));
                assertNotNull(onLoop(() -> backendPipeline.get(CompressedKeep.NAME)), "the keep stays until an unlink");
            }
            backend.proxySide().close().sync();
            client.proxySide().close().sync();
        }
        assertArrayEquals(sent.get(0), sent.get(1), "the bytes the encoder sends with the switch off");
    }

    @Test
    void readPastABackendDecoderRemovedAfterAMoveWithPinnedNeighboursIsDeliveredAsWithTheSwitchOff() throws Exception {
        for (boolean moveClient : new boolean[] {true, false})
            pinnedNeighboursOfARemovedCodec(true, moveClient, false, false);
    }

    @Test
    void writePastAClientEncoderRemovedAfterAMoveWithPinnedNeighboursIsDeliveredAsWithTheSwitchOff() throws Exception {
        for (boolean own : new boolean[] {false, true}) pinnedNeighboursOfARemovedCodec(false, true, own, false);
    }

    @Test
    void readPastABackendDecoderRemovedAfterALinkWhileTheBackendIsUnregisteredIsDeliveredAsWithTheSwitchOff()
            throws Exception {
        pinnedNeighboursOfARemovedCodec(true, false, false, true);
    }

    /**
     * Pins the handlers on either side of the backend decoder, or else of the client encoder, to the first loop (with
     * {@code own}, the tail-side one where and as the plugin adds its packet encoder), adds the codec with no executor,
     * moves the client when {@code moveClient} and then the backend to a second loop (with {@code unregisteredAtLink},
     * the backend is deregistered before the link and registered there after it), removes the codec there and holds
     * that loop while the first fires a read from the handler before the decoder, or a write from the handler after the
     * encoder; compares what reaches the handler past the codec before and after the second loop resumes with the switch
     * off.
     */
    private void pinnedNeighboursOfARemovedCodec(
            boolean read, boolean moveClient, boolean own, boolean unregisteredAtLink) throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        EventLoop first = group.next();
        List<List<Integer>> counts = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                Pair client = connect(true);
                Channel toClient = client.proxySide();
                var reached = new AtomicInteger();
                String tail = own ? CultEncoder.NAME : "tail";
                CompressedForwarder forwarder = onLoop(() -> {
                    var pipeline = toClient.pipeline();
                    pipeline.addLast(first, "head-side", new ChannelOutboundHandlerAdapter() {
                        @Override
                        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                            reached.incrementAndGet();
                            ReferenceCountUtil.release(msg);
                            promise.setSuccess();
                        }
                    });
                    var compressor = new SharedCompressor();
                    pipeline.addLast(
                            VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
                    pipeline.addLast(
                            CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD, true, compressor));
                    if (!own) pipeline.addLast(first, tail, new ChannelOutboundHandlerAdapter());
                    pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
                    pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
                    if (own) {
                        pipeline.addBefore(
                                first, "minecraft-decoder", CultDecoder.NAME, new ChannelInboundHandlerAdapter());
                        pipeline.addBefore(first, "minecraft-encoder", tail, new ChannelOutboundHandlerAdapter());
                    }
                    return on
                            ? VelocityCompressedForward.install(
                                    pipeline, CompressEncoder.class, CompressDecoder.class, COMPRESSORS)
                            : null;
                });
                if (on) assertNotNull(forwarder, "handlers pinned to the client's own loop pass the installation");
                Pair backend = connect(false);
                Channel fromBackend = backend.proxySide();
                onLoop(() -> {
                    var pipeline = fromBackend.pipeline();
                    pipeline.addLast(first, "source", new ChannelInboundHandlerAdapter());
                    pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
                    pipeline.addLast(first, "relay", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            reached.incrementAndGet();
                            ReferenceCountUtil.release(msg);
                        }
                    });
                    return null;
                });
                if (unregisteredAtLink) fromBackend.deregister().sync();
                onLoop(() -> {
                    if (on) {
                        VelocityCompressedForward.link(
                                forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
                        assertEquals(
                                unregisteredAtLink ? CompressedKeep.UNREGISTERED : "linked", forwarder.linkStatus());
                    }
                    return null;
                });
                if (moveClient) moveTo(second, toClient);
                if (unregisteredAtLink) second.register(fromBackend).sync();
                else moveTo(second, fromBackend);
                Channel codecs = read ? fromBackend : toClient;
                within(
                        codecs,
                        () -> codecs.pipeline()
                                .remove(
                                        read
                                                ? VelocityCompressedForward.COMPRESSION_DECODER
                                                : CompressedForwarder.COMPRESSION_ENCODER));
                if (on) {
                    assertNull(within(fromBackend, () -> fromBackend.pipeline().get(CompressedKeep.NAME)));
                    assertEquals(
                            moveClient ? null : forwarder,
                            within(toClient, () -> toClient.pipeline().get(CompressedForwarder.NAME)),
                            "a deregistered client loses its forwarder");
                    assertEquals(
                            moveClient ? "none" : CompressedKeep.UNREGISTERED, within(toClient, forwarder::linkStatus));
                }
                var holding = new CountDownLatch(1);
                var resume = new CountDownLatch(1);
                second.next().execute(() -> {
                    holding.countDown();
                    await(resume);
                });
                List<Integer> seen = new ArrayList<>();
                try {
                    await(holding);
                    seen.add(first.submit(() -> {
                                if (read)
                                    fromBackend
                                            .pipeline()
                                            .context("source")
                                            .fireChannelRead(Unpooled.buffer().writeByte(7));
                                else
                                    toClient.pipeline()
                                            .context(tail)
                                            .write(Unpooled.buffer().writeByte(7));
                                return reached.get();
                            })
                            .get(5, TimeUnit.SECONDS));
                } finally {
                    resume.countDown();
                }
                within(codecs, () -> null);
                seen.add(first.submit(reached::get).get(5, TimeUnit.SECONDS));
                counts.add(seen);
                fromBackend.close().sync();
                toClient.close().sync();
            }
            assertEquals(List.of(1, 1), counts.get(0), "delivered on the first loop with the switch off");
            assertEquals(counts.get(0), counts.get(1), "before and after the second loop resumes");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void clientRegisteredElsewhereBeforeItsForwarderSeesTheUnregistrationForwardsNothingAsWithTheSwitchOff()
            throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        byte[] packet = packet(4000, 110);
        List<byte[]> sent = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                synchronized (received) {
                    received.reset();
                }
                Pair client = client(on);
                Channel toClient = client.proxySide();
                var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                Pair backend = backend(second, relay(toClient));
                Channel fromBackend = backend.proxySide();
                backend.remote()
                        .writeAndFlush(Unpooled.wrappedBuffer(frame(body(packet, THRESHOLD))))
                        .sync();
                var failure = new AtomicReference<Throwable>();
                onLoop(() -> toClient.pipeline().addFirst("mover", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelUnregistered(ChannelHandlerContext ctx) {
                        try {
                            // A register can begin once the channel reads unregistered, before this event moves on.
                            assertTrue(second.next().register(ctx.channel()).await(5, TimeUnit.SECONDS));
                            fromBackend
                                    .eventLoop()
                                    .submit(() -> {
                                        if (on)
                                            VelocityCompressedForward.link(
                                                    forwarder,
                                                    toClient,
                                                    fromBackend,
                                                    CompressDecoder.class,
                                                    COMPRESSORS);
                                        fromBackend.config().setAutoRead(true);
                                        fromBackend.read();
                                        return null;
                                    })
                                    .get(5, TimeUnit.SECONDS);
                        } catch (Throwable thrown) {
                            failure.set(thrown);
                        }
                        ctx.fireChannelUnregistered();
                    }
                }));
                toClient.deregister().sync();
                within(toClient, () -> null);
                assertNull(failure.get());
                assertSame(fromBackend.eventLoop(), toClient.eventLoop());
                var frames = awaitFrames(1);
                assertPackets(List.of(packet), frames);
                sent.add(frames.getFirst().body());
                if (on) {
                    assertEquals(0L, within(toClient, forwarder::forwarded));
                    assertEquals("removed", within(toClient, forwarder::status));
                    assertNull(within(toClient, () -> toClient.pipeline().get(CompressedForwarder.NAME)));
                }
                fromBackend.close().sync();
                toClient.close().sync();
            }
            assertArrayEquals(sent.get(0), sent.get(1), "the frame the encoder compresses with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void linkTaskQueuedBeforeTheClientLeavesItsLoopChangesNothingAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        try {
            // Unregistered on its loop behind a handler that keeps the event; then registered on a held second loop.
            for (boolean elsewhere : new boolean[] {false, true}) {
                List<List<String>> seen = new ArrayList<>();
                for (boolean on : new boolean[] {false, true}) {
                    Pair client = client(on);
                    Channel toClient = client.proxySide();
                    var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                    Channel fromBackend =
                            backend(elsewhere ? second : group, relay(toClient)).proxySide();
                    var holding = new CountDownLatch(1);
                    var resume = new CountDownLatch(1);
                    var failure = new AtomicReference<Throwable>();
                    onLoop(() -> toClient.pipeline().addFirst("mover", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) {
                            if (!elsewhere) return;
                            try {
                                assertTrue(second.next().register(ctx.channel()).await(5, TimeUnit.SECONDS));
                                second.next().execute(() -> {
                                    holding.countDown();
                                    await(resume);
                                });
                                await(holding);
                            } catch (Throwable thrown) {
                                failure.set(thrown);
                            }
                            ctx.fireChannelUnregistered();
                        }
                    }));
                    var proxy = mock(ProxyServer.class);
                    var events = mock(EventManager.class);
                    when(proxy.getEventManager()).thenReturn(events);
                    Object plugin = new Object();
                    var forward = new VelocityCompressedForward(
                            proxy,
                            plugin,
                            player -> toClient,
                            new VelocityCompressedForward.Codecs(
                                    CompressEncoder.class, CompressDecoder.class, COMPRESSORS));
                    forward.listen();
                    @SuppressWarnings("unchecked")
                    ArgumentCaptor<EventHandler<ServerPostConnectEvent>> postConnected =
                            ArgumentCaptor.forClass(EventHandler.class);
                    verify(events).register(eq(plugin), eq(ServerPostConnectEvent.class), postConnected.capture());
                    var connection = mock(BackendServer.class);
                    when(connection.getConnection()).thenReturn(new BackendConnection(fromBackend));
                    Player player = mock(Player.class);
                    when(player.getCurrentServer()).thenReturn(Optional.of(connection));
                    try {
                        onLoop(() -> {
                            toClient.deregister();
                            // The link task is queued on this loop behind the deregistration's own task.
                            postConnected.getValue().execute(new ServerPostConnectEvent(player, null));
                            return null;
                        });
                        onLoop(() -> null);
                        assertNull(failure.get());
                        assertEquals(elsewhere, toClient.isRegistered(), "registered only on the held loop");
                        seen.add(fromBackend.pipeline().names());
                        if (on) assertEquals("none", forwarder.linkStatus());
                    } finally {
                        resume.countDown();
                    }
                    if (!elsewhere) group.next().register(toClient).sync();
                    within(toClient, () -> null);
                    fromBackend.close().sync();
                    toClient.close().sync();
                }
                assertFalse(seen.get(0).contains(CompressedKeep.NAME));
                assertEquals(seen.get(0), seen.get(1), "the backend's handlers with the switch off");
            }
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void endedLinkHoldsNoReferenceToItsClosedBackend() throws Exception {
        Pair client = client();
        Channel toClient = client.proxySide();
        var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
        Channel fromBackend = backend(toClient).proxySide();
        Object keep = onLoop(() -> {
            VelocityCompressedForward.link(forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
            return fromBackend.pipeline().get(CompressedKeep.NAME);
        });
        assertNotNull(keep);
        onLoop(() -> {
            forwarder.disable("threshold");
            return null;
        });
        assertSame(keep, onLoop(() -> fromBackend.pipeline().get(CompressedKeep.NAME)), "an ended link's keep stays");
        closeFully(fromBackend);
        assertNull(onLoop(() -> fromBackend.pipeline().get(CompressedKeep.NAME)));
        onLoop(() -> {
            assertHoldsNeither(forwarder, fromBackend, keep);
            return null;
        });
        toClient.close().sync();
    }

    /** Asserts that no field of {@code forwarder} is {@code backend} or {@code keep} and each reference is weak. */
    private static void assertHoldsNeither(CompressedForwarder forwarder, Channel backend, Object keep)
            throws IllegalAccessException {
        for (Field field : CompressedForwarder.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(forwarder);
            assertNotSame(backend, value, field.getName());
            assertNotSame(keep, value, field.getName());
            if (value instanceof Reference<?>) assertInstanceOf(WeakReference.class, value, field.getName());
        }
    }

    @Test
    void clientUnregisteredAtInstallGetsNoForwarderAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        List<List<String>> seen = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                Channel toClient = client(false).proxySide();
                toClient.deregister().sync();
                // An install queued on the client's loop can run after its deregistration.
                if (on)
                    assertNull(onLoop(() -> VelocityCompressedForward.install(
                            toClient.pipeline(), CompressEncoder.class, CompressDecoder.class, COMPRESSORS)));
                second.register(toClient).sync();
                seen.add(within(toClient, () -> toClient.pipeline().names()));
                toClient.close().sync();
            }
            assertFalse(seen.get(1).contains(CompressedForwarder.NAME));
            assertEquals(seen.get(0), seen.get(1), "the client's handlers on the second loop with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void keepUnregisteredOnAnotherLoopEndsItsLinkOnTheClientsLoopAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        List<List<String>> names = new ArrayList<>();
        List<List<String>> events = new ArrayList<>();
        List<Integer> pending = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                Channel toClient = client(on).proxySide();
                var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                var seen = new CopyOnWriteArrayList<String>();
                var reached = new CountDownLatch(1);
                Channel fromBackend = backend(group, new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelUnregistered(ChannelHandlerContext ctx) {
                                seen.add("unregistered on "
                                        + Thread.currentThread().getName());
                                reached.countDown();
                                ctx.fireChannelUnregistered();
                            }
                        })
                        .proxySide();
                Object keep = onLoop(() -> {
                    if (on)
                        VelocityCompressedForward.link(
                                forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
                    return fromBackend.pipeline().get(CompressedKeep.NAME);
                });
                assertEquals(on, keep != null);
                var resume = new CountDownLatch(1);
                var failure = new AtomicReference<Throwable>();
                onLoop(() -> fromBackend.pipeline().addFirst("mover", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelUnregistered(ChannelHandlerContext ctx) {
                        try {
                            // A register can begin once the channel reads unregistered, before this event moves on.
                            assertTrue(second.next().register(ctx.channel()).await(5, TimeUnit.SECONDS));
                        } catch (Throwable thrown) {
                            failure.set(thrown);
                        }
                        ctx.fireChannelUnregistered();
                        // The client's loop stays held while the second loop runs the rest of the event.
                        await(resume);
                    }
                }));
                awaitIdle(fromBackend);
                ChannelFuture deregistered = fromBackend.deregister();
                try {
                    await(reached);
                    assertNull(failure.get());
                    pending.add(((SingleThreadEventExecutor) group.next()).pendingTasks());
                    if (on) assertEquals("linked", forwarder.linkStatus(), "no link field written on the second loop");
                    names.add(within(fromBackend, () -> fromBackend.pipeline().names()));
                } finally {
                    resume.countDown();
                }
                deregistered.sync();
                if (on)
                    onLoop(() -> {
                        assertEquals(CompressedKeep.UNREGISTERED, forwarder.linkStatus());
                        assertHoldsNeither(forwarder, fromBackend, keep);
                        return null;
                    });
                events.add(List.copyOf(seen));
                fromBackend.close().sync();
                toClient.close().sync();
            }
            assertFalse(names.get(1).contains(CompressedKeep.NAME));
            assertEquals(names.get(0), names.get(1), "the backend's handlers with the switch off");
            assertEquals(events.get(0), events.get(1), "the later handler's event and thread with the switch off");
            assertEquals(pending.get(0) + 1, pending.get(1), "one queued unlink on the held loop");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void keepsQueuedUnlinkChangesNothingOnceTheClientHasLeftTheForwardersLoop() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        try {
            Channel toClient = client().proxySide();
            var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
            Channel fromBackend = backend(toClient).proxySide();
            onLoop(() -> {
                VelocityCompressedForward.link(forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
                toClient.pipeline().addFirst("keeper", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelUnregistered(ChannelHandlerContext ctx) {}
                });
                return null;
            });
            moveTo(second, toClient);
            assertSame(forwarder, within(toClient, () -> toClient.pipeline().get(CompressedForwarder.NAME)));
            // The keep's event runs on the forwarder's loop, which the client has left, and queues its unlink there.
            fromBackend.deregister().sync();
            onLoop(() -> null);
            assertNull(onLoop(() -> fromBackend.pipeline().get(CompressedKeep.NAME)));
            assertEquals(
                    "linked",
                    within(toClient, forwarder::linkStatus),
                    "no link field written on a loop the client left");
            group.next().register(fromBackend).sync();
            fromBackend.close().sync();
            toClient.close().sync();
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void unregistrationAndCloseReachTheHandlersAfterTheAddedOnesAsWithTheSwitchOff() throws Exception {
        for (boolean close : new boolean[] {false, true}) {
            List<List<String>> seen = new ArrayList<>();
            for (boolean on : new boolean[] {false, true}) {
                Channel toClient = client(on).proxySide();
                var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                var events = new CopyOnWriteArrayList<String>();
                Channel fromBackend =
                        backend(group, recorder("backend", events)).proxySide();
                onLoop(() -> {
                    toClient.pipeline().addLast(recorder("client", events));
                    if (on) {
                        VelocityCompressedForward.link(
                                forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
                        assertEquals("linked", forwarder.linkStatus());
                    }
                    return null;
                });
                for (Channel channel : List.of(fromBackend, toClient)) {
                    if (close) closeFully(channel);
                    else channel.deregister().sync();
                }
                seen.add(List.copyOf(events));
                if (!close)
                    for (Channel channel : List.of(fromBackend, toClient)) {
                        group.next().register(channel).sync();
                        channel.close().sync();
                    }
            }
            assertEquals(seen.get(0), seen.get(1), close ? "at a close" : "at a deregistration");
        }
    }

    /** Records each channelInactive and channelUnregistered with its thread, and passes it on. */
    private static ChannelInboundHandlerAdapter recorder(String side, List<String> events) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                events.add(side + " inactive on " + Thread.currentThread().getName());
                ctx.fireChannelInactive();
            }

            @Override
            public void channelUnregistered(ChannelHandlerContext ctx) {
                events.add(side + " unregistered on " + Thread.currentThread().getName());
                ctx.fireChannelUnregistered();
            }
        };
    }

    @Test
    void keepLeftOnAChannelThatLeftItsLoopPublishesNothingAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        byte[] packet = packet(4000, 111);
        try {
            // A head-side handler that keeps the event leaves the added handlers on the client, or the backend it
            // moves.
            for (boolean moveBackend : new boolean[] {false, true}) {
                List<List<Boolean>> seen = new ArrayList<>();
                for (boolean on : new boolean[] {false, true}) {
                    Channel toClient = client(on).proxySide();
                    var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                    var published = new CopyOnWriteArrayList<Boolean>();
                    var read = new CountDownLatch(1);
                    Pair backend = backend(group, new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            published.add(forwarder != null && forwarder.markBuf == msg);
                            ReferenceCountUtil.release(msg);
                            read.countDown();
                        }
                    });
                    Channel fromBackend = backend.proxySide();
                    Channel moved = moveBackend ? fromBackend : toClient;
                    onLoop(() -> {
                        if (on)
                            VelocityCompressedForward.link(
                                    forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
                        moved.pipeline().addFirst("keeper", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelUnregistered(ChannelHandlerContext ctx) {}
                        });
                        return null;
                    });
                    if (moveBackend) moveTo(second, fromBackend);
                    else toClient.deregister().sync();
                    if (on) {
                        assertEquals("linked", within(toClient, forwarder::linkStatus));
                        assertNotNull(
                                within(fromBackend, () -> fromBackend.pipeline().get(CompressedKeep.NAME)));
                    }
                    serve(backend, packet);
                    await(read);
                    seen.add(List.copyOf(published));
                    if (!moveBackend) group.next().register(toClient).sync();
                    fromBackend.close().sync();
                    toClient.close().sync();
                }
                assertEquals(List.of(false), seen.get(0));
                assertEquals(seen.get(0), seen.get(1), moveBackend ? "a backend moved" : "a client unregistered");
            }
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void installOffTheClientsLoopBehindAQueuedDeregistrationAddsNothingAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        EventLoop first = group.next();
        List<List<Integer>> seen = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                Channel toClient = connect(true).proxySide();
                var reached = new AtomicInteger();
                onLoop(() -> {
                    var pipeline = toClient.pipeline();
                    pipeline.addLast(first, "head-side", new ChannelOutboundHandlerAdapter() {
                        @Override
                        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                            reached.incrementAndGet();
                            ReferenceCountUtil.release(msg);
                            promise.setSuccess();
                        }
                    });
                    var compressor = new SharedCompressor();
                    pipeline.addLast(
                            VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
                    pipeline.addLast(
                            CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD, true, compressor));
                    pipeline.addLast(first, "tail-side", new ChannelOutboundHandlerAdapter());
                    pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
                    pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
                    return null;
                });
                var queued = new CountDownLatch(1);
                var go = new CountDownLatch(1);
                var deregistered = new AtomicReference<ChannelFuture>();
                first.execute(() -> {
                    deregistered.set(toClient.deregister());
                    queued.countDown();
                    await(go);
                });
                try {
                    await(queued);
                    if (on)
                        assertNull(VelocityCompressedForward.install(
                                toClient.pipeline(), CompressEncoder.class, CompressDecoder.class, COMPRESSORS));
                } finally {
                    go.countDown();
                }
                deregistered.get().sync();
                second.register(toClient).sync();
                within(toClient, () -> toClient.pipeline().remove(CompressedForwarder.COMPRESSION_ENCODER));
                var holding = new CountDownLatch(1);
                var resume = new CountDownLatch(1);
                second.next().execute(() -> {
                    holding.countDown();
                    await(resume);
                });
                int held;
                try {
                    await(holding);
                    held = first.submit(() -> {
                                toClient.pipeline()
                                        .context("tail-side")
                                        .write(Unpooled.buffer().writeByte(7));
                                return reached.get();
                            })
                            .get(5, TimeUnit.SECONDS);
                } finally {
                    resume.countDown();
                }
                within(toClient, () -> null);
                seen.add(List.of(held, first.submit(reached::get).get(5, TimeUnit.SECONDS)));
                toClient.close().sync();
            }
            assertEquals(List.of(1, 1), seen.get(0));
            assertEquals(seen.get(0), seen.get(1), "the head-side handler's writes while the second loop is held");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void installHeldAtTheMonitorWhileTheClientIsRegisteredElsewhereGetsNoForwarderAsWithTheSwitchOff()
            throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        List<List<String>> seen = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                Channel toClient = client(false).proxySide();
                ChannelPipeline pipeline = toClient.pipeline();
                Thread loop = onLoop(Thread::currentThread);
                toClient.deregister().sync();
                Future<CompressedForwarder> install = null;
                synchronized (pipeline) {
                    if (on) {
                        install = group.next()
                                .submit(() -> VelocityCompressedForward.install(
                                        pipeline, CompressEncoder.class, CompressDecoder.class, COMPRESSORS));
                        awaitBlockedOn(loop, pipeline);
                    }
                    second.register(toClient).sync();
                }
                if (on) assertNull(install.get(5, TimeUnit.SECONDS));
                seen.add(within(toClient, pipeline::names));
                toClient.close().sync();
            }
            assertFalse(seen.get(1).contains(CompressedForwarder.NAME));
            assertEquals(seen.get(0), seen.get(1), "the client's handlers on the second loop with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void linkHeldWhileItsBackendIsRegisteredElsewhereGetsNoKeepAsWithTheSwitchOff() throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        List<List<String>> seen = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                Channel toClient = client(on).proxySide();
                var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                Channel oldBackend = backend(toClient).proxySide();
                Channel newBackend = backend(toClient).proxySide();
                Thread loop = onLoop(() -> {
                    if (on) {
                        VelocityCompressedForward.link(
                                forwarder, toClient, oldBackend, CompressDecoder.class, COMPRESSORS);
                        assertEquals("linked", forwarder.linkStatus());
                    }
                    return Thread.currentThread();
                });
                newBackend.deregister().sync();
                Future<?> link = null;
                // The link task waits inside its unlink of the old backend while the new one is registered elsewhere.
                synchronized (oldBackend.pipeline()) {
                    if (on) {
                        link = group.next()
                                .submit(() -> VelocityCompressedForward.link(
                                        forwarder, toClient, newBackend, CompressDecoder.class, COMPRESSORS));
                        awaitBlockedOn(loop, oldBackend.pipeline());
                    }
                    second.register(newBackend).sync();
                }
                if (on) {
                    link.get(5, TimeUnit.SECONDS);
                    assertEquals("loop", onLoop(forwarder::linkStatus));
                }
                seen.add(within(newBackend, () -> newBackend.pipeline().names()));
                newBackend.close().sync();
                oldBackend.close().sync();
                toClient.close().sync();
            }
            assertFalse(seen.get(1).contains(CompressedKeep.NAME));
            assertEquals(seen.get(0), seen.get(1), "the new backend's handlers on the second loop with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    void keepRemovedByOtherCodeEndsItsLinkAndHoldsNoReferenceToItsClosedBackend() throws Exception {
        Channel toClient = client().proxySide();
        var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
        Channel fromBackend = backend(toClient).proxySide();
        Object keep = onLoop(() -> {
            VelocityCompressedForward.link(forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
            return fromBackend.pipeline().remove(CompressedKeep.NAME);
        });
        assertNotNull(keep);
        fromBackend.close().sync();
        onLoop(() -> {
            assertEquals("backend-adjacency", forwarder.linkStatus());
            assertHoldsNeither(forwarder, fromBackend, keep);
            return null;
        });
        toClient.close().sync();
    }

    @Test
    void keepOnTheClientsLoopEndsItsLinkBeforeItsEventMovesOn() throws Exception {
        for (boolean close : new boolean[] {false, true}) {
            Channel toClient = client().proxySide();
            var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
            var status = new AtomicReference<String>();
            Channel fromBackend = backend(group, new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) {
                            status.compareAndSet(null, forwarder.linkStatus());
                            ctx.fireChannelUnregistered();
                        }
                    })
                    .proxySide();
            onLoop(() -> {
                VelocityCompressedForward.link(forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
                assertEquals("linked", forwarder.linkStatus());
                return null;
            });
            if (close) closeFully(fromBackend);
            else fromBackend.deregister().sync();
            assertEquals(close ? CompressedKeep.CLOSED : CompressedKeep.UNREGISTERED, status.get());
            if (!close) {
                group.next().register(fromBackend).sync();
                fromBackend.close().sync();
            }
            toClient.close().sync();
        }
    }

    @Test
    void keepLeftByAHandlerThatKeptTheUnregistrationReadsClosedAtTheBackendsClose() throws Exception {
        Channel toClient = client().proxySide();
        var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
        Channel fromBackend = backend(toClient).proxySide();
        onLoop(() -> {
            VelocityCompressedForward.link(forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
            fromBackend.pipeline().addFirst("keeper", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelUnregistered(ChannelHandlerContext ctx) {}
            });
            return null;
        });
        closeFully(fromBackend);
        assertEquals(CompressedKeep.CLOSED, onLoop(forwarder::linkStatus));
        toClient.close().sync();
    }

    @Test
    void clientLeavingWithALiveLinkQueuesNoTaskBeyondTheSwitchedOffPipeline() throws Exception {
        for (boolean close : new boolean[] {false, true}) {
            List<Integer> seen = new ArrayList<>();
            for (boolean on : new boolean[] {false, true}) {
                Channel toClient = client(on).proxySide();
                var forwarder = (CompressedForwarder) toClient.pipeline().get(CompressedForwarder.NAME);
                Channel fromBackend = backend(toClient).proxySide();
                var pending = new AtomicInteger(-1);
                var read = new CountDownLatch(1);
                onLoop(() -> {
                    if (on)
                        VelocityCompressedForward.link(
                                forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
                    toClient.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) {
                            if (pending.compareAndSet(-1, ((SingleThreadEventExecutor) ctx.executor()).pendingTasks()))
                                read.countDown();
                            ctx.fireChannelUnregistered();
                        }
                    });
                    return null;
                });
                // The count is read with no task of this thread or of an earlier channel queued on the loop.
                awaitIdle(toClient);
                if (close) toClient.close();
                else toClient.deregister();
                await(read);
                seen.add(pending.get());
                if (!close) group.next().register(toClient).sync();
                closeFully(toClient);
                closeFully(fromBackend);
            }
            assertEquals(seen.get(0), seen.get(1), close ? "at a close" : "at a deregistration");
        }
    }

    @Test
    void keepsUnlinkRefusedByAShutDownInstallLoopStillLeavesTheBackendAsWithTheSwitchOff() throws Exception {
        List<List<String>> seen = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            var first = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
            var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
            try {
                Channel toClient = client(false).proxySide();
                moveTo(first, toClient);
                var events = new CopyOnWriteArrayList<String>();
                Channel fromBackend = backend(first, new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelUnregistered(ChannelHandlerContext ctx) {
                                events.add("unregistered");
                                ctx.fireChannelUnregistered();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                events.add(cause.getClass().getSimpleName());
                            }
                        })
                        .proxySide();
                var kept = new AtomicInteger();
                within(toClient, () -> {
                    if (on) {
                        var forwarder = VelocityCompressedForward.install(
                                toClient.pipeline(), CompressEncoder.class, CompressDecoder.class, COMPRESSORS);
                        VelocityCompressedForward.link(
                                forwarder, toClient, fromBackend, CompressDecoder.class, COMPRESSORS);
                        assertEquals("linked", forwarder.linkStatus());
                    }
                    toClient.pipeline().addFirst("keeper", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) {}
                    });
                    fromBackend.pipeline().addFirst("keeper", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) {
                            if (kept.getAndIncrement() > 0) ctx.fireChannelUnregistered();
                        }
                    });
                    return null;
                });
                moveTo(second, toClient, fromBackend);
                first.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
                // The keep's event runs on the second loop and hands its unlink to the shut-down install loop.
                fromBackend.deregister().sync();
                seen.add(List.copyOf(events));
                seen.add(fromBackend.pipeline().names());
                second.register(fromBackend).sync();
                fromBackend.close().sync();
                toClient.close().sync();
            } finally {
                first.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
                second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
            }
        }
        assertEquals(List.of("unregistered"), seen.get(0));
        assertEquals(seen.subList(0, 2), seen.subList(2, 4), "the backend's events and handlers with the switch off");
    }

    @Test
    void installWhileTheClientIsRegisteredElsewhereAfterTheEntryCheckGetsNoForwarderAsWithTheSwitchOff()
            throws Exception {
        var second = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        List<List<String>> seen = new ArrayList<>();
        try {
            for (boolean on : new boolean[] {false, true}) {
                var toClient = new HookedChannel();
                group.register(toClient).sync();
                toClient.connect(serverChannel.localAddress()).sync();
                Channel remote = accepted.poll(5, TimeUnit.SECONDS);
                onLoop(() -> {
                    var pipeline = toClient.pipeline();
                    var compressor = new SharedCompressor();
                    pipeline.addLast(
                            VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
                    pipeline.addLast(
                            CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD, true, compressor));
                    return null;
                });
                toClient.deregister().sync();
                if (on) {
                    // The register completes inside install's first read of the client's loop, after that read.
                    assertNull(onLoop(() -> {
                        toClient.arm(() -> second.register(toClient).syncUninterruptibly());
                        return VelocityCompressedForward.install(
                                toClient.pipeline(), CompressEncoder.class, CompressDecoder.class, COMPRESSORS);
                    }));
                } else {
                    second.register(toClient).sync();
                }
                seen.add(within(toClient, () -> toClient.pipeline().names()));
                toClient.close().sync();
                remote.close().sync();
            }
            assertFalse(seen.get(1).contains(CompressedForwarder.NAME));
            assertEquals(seen.get(0), seen.get(1), "the client's handlers on the second loop with the switch off");
        } finally {
            second.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    /** A local channel that runs a hook once, inside the next read of its event loop on the thread that armed it. */
    private static final class HookedChannel extends LocalChannel {
        private volatile Thread armed;
        private Runnable hook;

        void arm(Runnable hook) {
            this.hook = hook;
            armed = Thread.currentThread();
        }

        @Override
        public EventLoop eventLoop() {
            EventLoop loop = super.eventLoop();
            Runnable run = hook;
            if (armed == Thread.currentThread() && run != null) {
                armed = null;
                hook = null;
                run.run();
            }
            return loop;
        }
    }

    /** Returns once a task on {@code channel}'s loop finds no other task queued there. */
    private static void awaitIdle(Channel channel) throws Exception {
        for (int i = 0; i < 100; i++)
            if (within(channel, () -> ((SingleThreadEventExecutor) channel.eventLoop()).pendingTasks() == 0)) return;
        fail("the loop stays busy");
    }

    /** Closes {@code channel} and returns once its loop has run the unregistration a close queues after its future. */
    private static void closeFully(Channel channel) throws Exception {
        channel.close().sync();
        within(channel, () -> null);
        within(channel, () -> null);
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
