package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.intake.v3.Capture;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Refused;
import ac.cult.cultac.cloud.intake.v3.Replies;
import ac.cult.cultac.cloud.intake.v3.Reply;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.grim.grimac.api.plugin.GrimPlugin;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.mockito.Mockito;

/** A local intake service and the plugin globals the connection tests run against. */
public final class ConnectionFixture {
    static final ProtocolData DATA = ProtocolData.load(ProtocolVersion.V26_3);
    static final int LOGIN = id("login");
    static final int CHUNK = id("level_chunk_with_light");
    static final int SPAWN = id("add_entity");
    static final int TICK_END =
            DATA.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND).id("minecraft:client_tick_end");

    private ConnectionFixture() {}

    public static long compressThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith("cult-cloud-compress-"))
                .count();
    }

    public static CloudSettings settings(String url, String key, List<Integer> protocols) {
        return new CloudSettings(url, key, CloudSettings.DEFAULT_SERVER_ID, "proxy", protocols, false, false);
    }

    /** One message of a socket's zstd frame, from its bytes and the messages before it on {@code zstd}. */
    static byte[] decompress(ZstdDecompressCtx zstd, byte[] message) {
        ByteBuffer in = ByteBuffer.allocateDirect(message.length - 1);
        in.put(message, 1, message.length - 1).flip();
        ByteBuffer out = ByteBuffer.allocateDirect(64 * 1024);
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        while (true) {
            out.clear();
            zstd.decompressDirectByteBufferStream(out, in);
            out.flip();
            byte[] chunk = new byte[out.remaining()];
            out.get(chunk);
            records.writeBytes(chunk);
            if (!in.hasRemaining() && chunk.length < out.capacity()) return records.toByteArray();
        }
    }

    public static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, what);
            Thread.sleep(10);
        }
    }

    private static int id(String name) {
        return DATA.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:" + name);
    }

    public static final class Receiver implements AutoCloseable {
        final NioEventLoopGroup group = new NioEventLoopGroup(1);
        public final BlockingQueue<ChannelHandlerContext> connects = new LinkedBlockingQueue<>();
        public final BlockingQueue<String> connectBodies = new LinkedBlockingQueue<>();
        public final BlockingQueue<Channel> handshakes = new LinkedBlockingQueue<>();
        public final BlockingQueue<IntakeMessage> messages = new LinkedBlockingQueue<>();
        /** The status code of each close frame a socket sent. */
        public final BlockingQueue<Integer> closes = new LinkedBlockingQueue<>();

        final List<byte[]> wire = new ArrayList<>();
        final AtomicInteger pings = new AtomicInteger();
        /** Pings answered before the rest are swallowed. */
        final AtomicInteger answeredPings = new AtomicInteger(Integer.MAX_VALUE);

        final Channel server;
        final CloudConnections connections;
        volatile boolean refuseJoin;
        /** Whether messages are kept on the wire only, unread, as on protocol 4's zstd stream. */
        volatile boolean unread;
        /** Whether a JOIN's answer asks for every clientbound packet. */
        volatile boolean keepAllClientbound;
        /** Replies each socket is sent as its handshake completes. */
        volatile byte[] greeting;

        public Receiver() throws InterruptedException {
            this(CloudConnection.Timing.DEFAULT);
        }

        /** A receiver whose registry's sockets run on {@code timing}. */
        Receiver(CloudConnection.Timing timing) throws InterruptedException {
            connections = new CloudConnections(timing);
            server = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(
                                            new HttpServerCodec(),
                                            new HttpObjectAggregator(16 * 1024),
                                            new SimpleChannelInboundHandler<FullHttpRequest>() {
                                                protected void channelRead0(
                                                        ChannelHandlerContext ctx, FullHttpRequest request) {
                                                    if (request.uri().equals("/api/v1/connect")) {
                                                        assertTrue(List.of("Bearer test-key", "Bearer other-key")
                                                                .contains(request.headers()
                                                                        .get(HttpHeaderNames.AUTHORIZATION)));
                                                        connectBodies.add(request.content()
                                                                .toString(StandardCharsets.UTF_8));
                                                        connects.add(ctx);
                                                    } else ctx.fireChannelRead(request.retain());
                                                }
                                            },
                                            new ChannelInboundHandlerAdapter() {
                                                public void channelRead(ChannelHandlerContext ctx, Object message) {
                                                    if (message instanceof CloseWebSocketFrame close)
                                                        closes.add(close.statusCode());
                                                    if (message instanceof PingWebSocketFrame ping) {
                                                        pings.incrementAndGet();
                                                        if (answeredPings.getAndDecrement() <= 0) {
                                                            ping.release();
                                                            return;
                                                        }
                                                    }
                                                    ctx.fireChannelRead(message);
                                                }
                                            },
                                            new WebSocketServerProtocolHandler(
                                                    "/intake", null, true, 2 * 1024 * 1024, false, true),
                                            new WebSocketFrameAggregator(2 * 1024 * 1024),
                                            new SimpleChannelInboundHandler<BinaryWebSocketFrame>() {
                                                /** The socket's one zstd frame, read message by message. */
                                                private final ZstdDecompressCtx zstd = new ZstdDecompressCtx();

                                                public void channelInactive(ChannelHandlerContext ctx)
                                                        throws Exception {
                                                    zstd.close();
                                                    super.channelInactive(ctx);
                                                }

                                                public void userEventTriggered(ChannelHandlerContext ctx, Object event)
                                                        throws Exception {
                                                    if (event
                                                            instanceof
                                                            WebSocketServerProtocolHandler.HandshakeComplete) {
                                                        if (greeting != null)
                                                            ctx.writeAndFlush(new BinaryWebSocketFrame(
                                                                    Unpooled.wrappedBuffer(greeting)));
                                                        handshakes.add(ctx.channel());
                                                    }
                                                    super.userEventTriggered(ctx, event);
                                                }

                                                protected void channelRead0(
                                                        ChannelHandlerContext ctx, BinaryWebSocketFrame frame)
                                                        throws Exception {
                                                    byte[] bytes = ByteBufUtil.getBytes(frame.content());
                                                    synchronized (wire) {
                                                        wire.add(bytes);
                                                    }
                                                    if (unread) return;
                                                    byte[] intake = bytes[0] == IntakeWire.COMPRESSED
                                                            ? decompress(zstd, bytes)
                                                            : Arrays.copyOfRange(bytes, 1, bytes.length);
                                                    for (IntakeMessage message : Intake.parseFrom(intake)
                                                            .getMessagesList()) {
                                                        messages.add(message);
                                                        if (message.hasJoin()) {
                                                            Capture capture = Capture.newBuilder()
                                                                    .setPlayer(message.getJoin()
                                                                            .getPlayer())
                                                                    .addClientbound(CHUNK)
                                                                    .addClientbound(SPAWN)
                                                                    .addClientbound(id("ping"))
                                                                    .addClientbound(id("container_set_content"))
                                                                    .addClientbound(id("set_held_slot"))
                                                                    .addServerbound(TICK_END)
                                                                    .addServerbound(DATA.packets(
                                                                                    ConnectionPhase.PLAY,
                                                                                    PacketDirection.SERVERBOUND)
                                                                            .id("minecraft:pong"))
                                                                    .addDimension(LOGIN)
                                                                    .addDimension(id("respawn"))
                                                                    .build();
                                                            Reply.Builder answer = Reply.newBuilder();
                                                            if (refuseJoin)
                                                                answer.setRefused(Refused.newBuilder()
                                                                        .setPlayer(message.getJoin()
                                                                                .getPlayer())
                                                                        .setReason("test refusal"));
                                                            else if (keepAllClientbound) {
                                                                Capture.Builder all = capture.toBuilder();
                                                                int ids = DATA.packets(
                                                                                ConnectionPhase.PLAY,
                                                                                PacketDirection.CLIENTBOUND)
                                                                        .size();
                                                                for (int id = 0; id < ids; id++) all.addClientbound(id);
                                                                answer.setCapture(all);
                                                            } else answer.setCapture(capture);
                                                            byte[] reply = Replies.newBuilder()
                                                                    .addReplies(answer)
                                                                    .build()
                                                                    .toByteArray();
                                                            ctx.writeAndFlush(new BinaryWebSocketFrame(
                                                                    Unpooled.wrappedBuffer(reply)));
                                                        }
                                                    }
                                                }
                                            });
                        }
                    })
                    .bind("127.0.0.1", 0)
                    .sync()
                    .channel();
        }

        public String url() {
            return "http://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort();
        }

        void allowConnect() throws InterruptedException {
            allowConnect("\"protocol\":5");
        }

        /** Answers the next connect with {@code terms}, the connect JSON's fields beside its url and message limit. */
        public void allowConnect(String terms) throws InterruptedException {
            ChannelHandlerContext ctx = connects.poll(5, TimeUnit.SECONDS);
            assertNotNull(ctx, "eager connect request");
            byte[] json = ("{\"url\":\"" + url().replace("http:", "ws:") + "/intake\"," + terms
                            + ",\"maxMessageBytes\":2097152}")
                    .getBytes(StandardCharsets.UTF_8);
            var response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(json));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, json.length);
            ctx.writeAndFlush(response);
        }

        /** Answers the next connect as a service that takes none of the protocols offered. */
        public void refuseConnect() throws InterruptedException {
            answerConnect(
                    HttpResponseStatus.UNPROCESSABLE_ENTITY, "no protocol in common".getBytes(StandardCharsets.UTF_8));
        }

        /** Answers the next connect with {@code status} and {@code body} as given. */
        void answerConnect(HttpResponseStatus status, byte[] body) throws InterruptedException {
            ChannelHandlerContext ctx = connects.poll(5, TimeUnit.SECONDS);
            assertNotNull(ctx, "connect request");
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
            ctx.writeAndFlush(response);
        }

        /** The socket URL connect answers with. */
        String socketUrl() {
            return url().replace("http:", "ws:") + "/intake";
        }

        IntakeMessage take(IntakeMessage.KindCase kind) throws InterruptedException {
            IntakeMessage message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, kind.name());
            assertEquals(kind, message.getKindCase());
            return message;
        }

        public void close() throws InterruptedException {
            connections.closeAll();
            server.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /** The plugin {@link CultAPI} hands out, with a logger the test reads; restored on close. */
    public static final class Globals implements AutoCloseable {
        final Field loaderField = CultAPI.class.getDeclaredField("loader");
        final Object previousLoader;
        public final List<LogRecord> logs = new ArrayList<>();

        public Globals() throws Exception {
            loaderField.setAccessible(true);
            previousLoader = loaderField.get(CultAPI.INSTANCE);
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                public void publish(LogRecord record) {
                    synchronized (logs) {
                        logs.add(record);
                    }
                }

                public void flush() {}

                public void close() {}
            });
            GrimPlugin plugin = Mockito.mock(GrimPlugin.class);
            Mockito.when(plugin.getLogger()).thenReturn(logger);
            PlatformLoader loader = Mockito.mock(PlatformLoader.class);
            Mockito.when(loader.getPlugin()).thenReturn(plugin);
            loaderField.set(CultAPI.INSTANCE, loader);
        }

        public void close() throws Exception {
            loaderField.set(CultAPI.INSTANCE, previousLoader);
        }
    }
}
