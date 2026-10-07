package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.UUID;
import java.util.concurrent.*;

/** Real independent I/O/packet loops around the manager's actual User creation and close. */
final class InboundConsumerFixture implements AutoCloseable {
    final DefaultEventLoopGroup io = new DefaultEventLoopGroup(2);
    final DefaultEventLoopGroup owners = new DefaultEventLoopGroup(1);
    final io.netty.util.concurrent.EventExecutor owner;
    final CultNetworkManager manager = new CultNetworkManager();
    final UUID uuid = UUID.randomUUID();
    final PacketDispatcher routes;
    final CompletableFuture<User> connected = new CompletableFuture<>();
    final CompletableFuture<User> observed = new CompletableFuture<>();
    final CompletableFuture<Void> received = new CompletableFuture<>();
    final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    final Channel listener, client, server;
    final CultConnection transport;

    InboundConsumerFixture() throws Exception {
        this(true);
    }

    InboundConsumerFixture(boolean separateOwner) throws Exception {
        ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.installConfig();
        var runtime = TestProtocolRuntime.create(ac.cult.cultac.protocol.data.ProtocolData.load(ProtocolVersion.V26_3));
        manager.configureTransport(runtime, () -> {}, () -> CompletableFuture.completedFuture(null));
        manager.setPacketOwnerResolver(channel -> separateOwner ? new PacketOwner(owners.next(), null) : null);
        manager.lifecycleHooks(new UserLifecycleHooks() {
            @Override
            public void onAuthenticated(User user) {
                assertTrue(owner.inEventLoop());
                connected.complete(user);
            }
        });
        manager.dispatcher()
                .register(batch -> batch.receive(
                        ServerboundPackets.PONG, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                            assertTrue(event.getUser().getPacketExecutor().inEventLoop());
                            assertSame(connected.join(), event.getUser());
                            assertSame(event.getUser(), player.user);
                            observed.complete(event.getUser());
                        }));

        var accepted = new CompletableFuture<Binding>();
        listener = new ServerBootstrap()
                .group(io)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(LocalChannel channel) throws Exception {
                        try {
                            var selectedOwner = separateOwner ? owners.next() : channel.eventLoop();
                            channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
                            channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
                            channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
                            channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
                            var platform =
                                    ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.platformConnection();
                            org.mockito.Mockito.when(platform.authenticatedProfile())
                                    .thenReturn(new User.Profile(uuid, "InboundConsumer"));
                            var wire = manager.createConnection(platform, channel);
                            wire.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
                            wire.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
                            CultDecoder.install(wire);
                            CultEncoder.install(wire);
                            channel.pipeline().addLast("bind_when_active", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    try {
                                        accepted.complete(new Binding(channel, wire, selectedOwner));
                                    } catch (Throwable failure) {
                                        accepted.completeExceptionally(failure);
                                    }
                                    ctx.fireChannelActive();
                                }
                            });
                            // Models the already-selected native interceptor's executor.
                            channel.pipeline().addLast("sink", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object message) {
                                    ReferenceCountUtil.release(message);
                                    received.complete(null);
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) {
                                    errors.add(failure);
                                    received.completeExceptionally(failure);
                                }
                            });
                        } catch (Throwable failure) {
                            accepted.completeExceptionally(failure);
                            throw failure;
                        }
                    }
                })
                .bind(new LocalAddress("cult-record-user-" + System.nanoTime()))
                .sync()
                .channel();
        client = new Bootstrap()
                .group(io)
                .channel(LocalChannel.class)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(listener.localAddress())
                .sync()
                .channel();
        Binding binding = accepted.get(5, TimeUnit.SECONDS);
        server = binding.channel;
        transport = binding.transport;
        routes = transport.dispatcher();
        owner = binding.owner;
    }

    void receive() throws Exception {
        ByteBuf frame = Unpooled.buffer();
        Wire.writeVarInt(
                frame,
                routes.runtime()
                        .data()
                        .packets(ConnectionPhase.CONFIGURATION, PacketDirection.SERVERBOUND)
                        .id("minecraft:pong"));
        frame.writeInt(7);
        server.eventLoop()
                .submit(() -> server.pipeline().fireChannelRead(frame))
                .get(5, TimeUnit.SECONDS);
        received.get(5, TimeUnit.SECONDS);
        assertEquals(0, frame.refCnt());
    }

    void receivePlay(int id) throws Exception {
        receivePlayFrame("minecraft:pong", frame -> frame.writeInt(id));
    }

    void receivePlayFrame(String name, java.util.function.Consumer<ByteBuf> payload) throws Exception {
        ByteBuf frame = Unpooled.buffer();
        Wire.writeVarInt(
                frame,
                routes.runtime()
                        .data()
                        .packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND)
                        .id(name));
        payload.accept(frame);
        server.eventLoop()
                .submit(() -> server.pipeline().fireChannelRead(frame))
                .get(5, TimeUnit.SECONDS);
        owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
        server.eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
        // Accepted bytes return through the I/O decoder before the final
        // owner handler releases them; wait for that last hop too.
        owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertEquals(0, frame.refCnt());
    }

    @Override
    public void close() throws Exception {
        client.close().sync();
        server.close().sync();
        listener.close().sync();
        owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
        io.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        owners.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
    }

    private record Binding(Channel channel, CultConnection transport, io.netty.util.concurrent.EventExecutor owner) {}
}
