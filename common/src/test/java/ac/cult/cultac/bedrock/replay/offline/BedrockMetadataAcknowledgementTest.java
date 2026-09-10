package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockNextTickStates;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputPluginMessage;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.events.packets.listeners.BedrockAuthInputPluginMessageListener;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.latency.GeyserQueue;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.network.netty.LocalChannelWithRemoteAddress;
import org.geysermc.geyser.network.netty.LocalServerChannelWrapper;
import org.geysermc.geyser.registry.PacketTranslatorRegistry;
import org.geysermc.geyser.session.DownstreamSession;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.UpstreamSession;
import org.geysermc.geyser.translator.protocol.bedrock.BedrockNetworkStackLatencyTranslator;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.*;

public final class BedrockMetadataAcknowledgementTest {
    @Test
    public void quietConnectionAppliesMetadataBeforeLaterMovement() throws Exception {
        checkOrdering(false);
    }

    /**
     * Capture 1789012065022, seq 19765/19766/19846/19869: self GLIDING=true,
     * its marker, its reply, then auth tick 10070. Exercise decoded Bedrock
     * packets through Geyser's real scheduling methods, MCProtocolLib sending,
     * and both ends of its local transport. No scheduling/send method is mocked.
     */
    @Test
    public void queuedMovementUsesTheSameGeyserQueuesAsTheLatencyReply() throws Exception {
        checkOrdering(true);
    }

    private static void checkOrdering(boolean queuePacketsTogether) throws Exception {
        OfflineCultTestBootstrap.installConfig();
        try (Transport transport = new Transport()) {
            transport.initialize();
            transport.recordMetadata();
            if (queuePacketsTogether) transport.holdServerLoop();
            try {
                transport.receive(auth(10069));
                if (!queuePacketsTogether) transport.drain();
                var reply = new NetworkStackLatencyPacket();
                reply.setTimestamp((long) transport.player.getLastClientboundBedrockTransaction().id() * 1_000_000L);
                reply.setFromServer(true);
                transport.receive(reply);
                if (!queuePacketsTogether) transport.drain();
                transport.receive(auth(10070));
                transport.drainGeyser();
            } finally {
                transport.releaseServer.countDown();
            }
            transport.lastAuth.get(5, TimeUnit.SECONDS);
            transport.drain();

            assertEquals(transport.arrivals.toString(), List.of(false, true), transport.glidingAtAuth);
            transport.server.submit(() -> {
                var current = state(transport.player);
                var dimensions = transport.player.bedrockState.applyConfirmedBoundingBoxSize(
                        current, BedrockInputFrame.idle(10070)).acknowledgedPlayerDimensions();
                assertNotNull(dimensions);
                assertEquals((double) 0.6F, dimensions.width(), 0.0);
                assertEquals((double) 0.6F, dimensions.height(), 0.0);
                var movement = BedrockAerialMovement.glideVelocity(current.velocity(),
                        new BedrockInputFrame(10070, 90.15979F, -28.132812F, false, false, true));
                assertEquals(-1.2017822265625, movement.x(), 0.00001);
                assertEquals(0.5762786865234375, movement.y(), 0.00001);
                assertEquals(-0.0033416748046875, movement.z(), 0.00001);
            }).get(5, TimeUnit.SECONDS);
        }
    }

    private static final class Transport implements AutoCloseable {
        final EventLoop bedrock = loop("metadata-bedrock");
        final EventLoop tick = loop("metadata-geyser-tick");
        final EventLoop downstream = loop("metadata-geyser-downstream");
        final EventLoop server = loop("metadata-java-server");
        final CountDownLatch releaseServer = new CountDownLatch(1);
        final CompletableFuture<Void> lastAuth = new CompletableFuture<>();
        final List<Boolean> glidingAtAuth = new ArrayList<>();
        final List<String> arrivals = new ArrayList<>();
        final GeyserQueue latency = new GeyserQueue();
        Channel listener;
        Channel client;
        Channel serverChannel;
        CultPlayer player;
        GeyserSession session;
        BedrockPacketHandler tap;
        Map<Object, Object> players;
        Map<Object, Object> users;
        Map<Object, Object> taps;

        void initialize() throws Exception {
            var accepted = new CompletableFuture<Channel>();
            listener = new ServerBootstrap().group(server).channel(LocalServerChannelWrapper.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) {
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<Packet>() {
                                @Override protected void channelRead0(ChannelHandlerContext context, Packet packet) {
                                    try {
                                        receiveJava(context, packet);
                                    } catch (Throwable error) {
                                        lastAuth.completeExceptionally(error);
                                    }
                                }

                                @Override public void exceptionCaught(ChannelHandlerContext context, Throwable error) {
                                    lastAuth.completeExceptionally(error);
                                }
                            });
                            accepted.complete(channel);
                        }
                    }).bind(LocalAddress.ANY).sync().channel();

            var protocol = Mockito.mock(MinecraftProtocol.class);
            Mockito.when(protocol.getOutboundState()).thenReturn(ProtocolState.GAME);
            var networkSession = new ClientNetworkSession(listener.localAddress(), protocol, tick, null, null);
            client = new Bootstrap().group(downstream).channel(LocalChannelWithRemoteAddress.class)
                    .handler(new ChannelInitializer<LocalChannelWithRemoteAddress>() {
                        @Override protected void initChannel(LocalChannelWithRemoteAddress channel) {
                            channel.spoofedRemoteAddress(new InetSocketAddress("127.0.0.1", 19132));
                            channel.pipeline().addLast(networkSession);
                        }
                    }).connect(listener.localAddress()).sync().channel();
            serverChannel = accepted.get(5, TimeUnit.SECONDS);
            player = server.submit(() -> {
                UUID uuid = UUID.randomUUID();
                var user = new User(new User.Profile(uuid, ".Metadata_Test"), null, null, null, serverChannel);
                return new CultPlayer(user, MovementPlatform.BEDROCK, new BedrockPlayerState(uuid));
            }).get(5, TimeUnit.SECONDS);
            server.submit(() -> { seedCarry(player); return null; }).get(5, TimeUnit.SECONDS);
            players = map(CultAPI.INSTANCE.getPlayerDataManager(), "playerDataMap");
            users = map(CultAPI.INSTANCE.getNetworkManager(), "currentUsersByUuid");
            taps = map(null, GeyserBedrockBridgeRuntime.class, "PACKET_TAPS");
            players.put(player.user, player);
            users.put(player.playerUUID, player.user);

            session = Mockito.mock(GeyserSession.class, Mockito.CALLS_REAL_METHODS);
            Mockito.doReturn(player.playerUUID).when(session).javaUuid();
            Mockito.doReturn("Metadata_Test").when(session).bedrockUsername();
            Mockito.doReturn(2169).when(session).protocolVersion();
            set(session, GeyserSession.class, "tickEventLoop", tick);
            set(session, GeyserSession.class, "protocol", protocol);
            set(session, GeyserSession.class, "downstream", new DownstreamSession(networkSession));
            set(session, GeyserSession.class, "upstream", Mockito.mock(UpstreamSession.class));
            set(session, GeyserSession.class, "latencyPingCache", latency);
            set(session, GeyserSession.class, "geyser", Mockito.mock(GeyserImpl.class, Mockito.RETURNS_DEEP_STUBS));
            var entity = Mockito.mock(SessionPlayerEntity.class);
            Mockito.when(entity.getLastTickEndVelocity()).thenReturn(Vector3f.ZERO);
            set(session, GeyserSession.class, "playerEntity", entity);

            var registry = PacketTranslatorRegistry.<BedrockPacket>create();
            registry.register(NetworkStackLatencyPacket.class, new BedrockNetworkStackLatencyTranslator());
            BedrockPacketHandler delegate = new BedrockPacketHandler() {
                @Override public PacketSignal handlePacket(BedrockPacket packet) {
                    if (packet instanceof NetworkStackLatencyPacket) {
                        assertTrue(bedrock.inEventLoop());
                        assertTrue(registry.translate(packet.getClass(), packet, session, false));
                    } else {
                        // The bridge forwards raw auth input before vanilla movement translation.
                        assertTrue(tick.inEventLoop());
                    }
                    return PacketSignal.HANDLED;
                }
            };
            Class<?> tapClass = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$PacketTapHandler");
            var constructor = tapClass.getDeclaredConstructor(GeyserSession.class, BedrockSession.class,
                    BedrockPacketHandler.class, GeyserQueue.class);
            constructor.setAccessible(true);
            tap = (BedrockPacketHandler) constructor.newInstance(session, null, delegate, latency);
            taps.put(session, tap);
        }

        void recordMetadata() throws Exception {
            tick.submit(() -> {
                var context = Mockito.mock(ChannelHandlerContext.class);
                Mockito.doAnswer(invocation -> {
                    ((BedrockPacketWrapper) invocation.getArgument(0)).release();
                    return null;
                }).when(context).write(Mockito.any(), Mockito.any());
                var source = BedrockPacketWrapper.create(0, 0, 0, new SetEntityDataPacket(), null);
                try {
                    var record = tap.getClass().getDeclaredMethod("recordOutboundMetadata", ChannelHandlerContext.class,
                            BedrockPacketWrapper.class, Float.class, Float.class, Boolean.class, Boolean.class, Boolean.class);
                    record.setAccessible(true);
                    record.invoke(tap, context, source, 0.6F, 0.6F, true, false, false);
                } finally {
                    source.release();
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
            server.submit(() -> {
                assertFalse(state(player).gliding());
                assertEquals("confirmedSize=null", player.bedrockState.boundingBoxStatus());
            }).get(5, TimeUnit.SECONDS);
        }

        void receive(BedrockPacket packet) throws Exception {
            bedrock.submit(() -> tap.handlePacket(packet)).get(5, TimeUnit.SECONDS);
        }

        void receiveJava(ChannelHandlerContext context, Packet packet) {
            assertTrue(server.inEventLoop());
            assertTrue(((Channel) player.user.getChannel()).eventLoop().inEventLoop());
            assertFalse(bedrock.inEventLoop());
            assertFalse(tick.inEventLoop());
            assertFalse(downstream.inEventLoop());
            if (packet instanceof ServerboundPongPacket pong) {
                arrivals.add("pong");
                var nms = new net.minecraft.network.protocol.common.ServerboundPongPacket(pong.getId());
                var event = new PacketReceiveEvent(player.user, nms, ConnectionProtocol.PLAY);
                new PacketPingListener().onPong(event, player, nms);
                assertTrue(event.isAcceptedTransactionResponse());
            } else if (packet instanceof ServerboundCustomPayloadPacket payload) {
                var frame = BedrockAuthInputPluginMessage.decode(payload.getData());
                if (frame != null) {
                    glidingAtAuth.add(state(player).gliding());
                    arrivals.add("auth:" + frame.getClientTick() + " gliding=" + state(player).gliding());
                    if (frame.getClientTick() == 10070) lastAuth.complete(null);
                } else {
                    arrivals.add("metadata");
                    var nms = new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                            new DiscardedPayload(Identifier.parse(BedrockAuthInputPluginMessage.CHANNEL), payload.getData()));
                    new BedrockAuthInputPluginMessageListener().onCustomPayload(
                            new PacketReceiveEvent(player.user, nms, ConnectionProtocol.PLAY), player, nms);
                }
            } else {
                fail("Unexpected Java packet: " + packet);
            }
        }

        void holdServerLoop() throws Exception {
            var entered = new CountDownLatch(1);
            server.execute(() -> {
                entered.countDown();
                try {
                    if (!releaseServer.await(5, TimeUnit.SECONDS)) lastAuth.completeExceptionally(new AssertionError("server gate timed out"));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    lastAuth.completeExceptionally(error);
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        }

        void drainGeyser() throws Exception {
            tick.submit(() -> {}).get(5, TimeUnit.SECONDS);
            downstream.submit(() -> {}).get(5, TimeUnit.SECONDS);
        }

        void drain() throws Exception {
            drainGeyser();
            server.submit(() -> {}).get(5, TimeUnit.SECONDS);
            // Also let the old acknowledgement's downstream round trip finish,
            // so the quiet-connection control passes on both implementations.
            downstream.submit(() -> {}).get(5, TimeUnit.SECONDS);
            server.submit(() -> {}).get(5, TimeUnit.SECONDS);
        }

        @Override public void close() {
            releaseServer.countDown();
            if (session != null && taps != null) taps.remove(session, tap);
            if (player != null) {
                if (players != null) players.remove(player.user, player);
                if (users != null) users.remove(player.playerUUID, player.user);
                server.submit(player::onRemove).syncUninterruptibly();
            }
            if (client != null) client.close().syncUninterruptibly();
            if (serverChannel != null) serverChannel.close().syncUninterruptibly();
            if (listener != null) listener.close().syncUninterruptibly();
            for (var loop : List.of(bedrock, tick, downstream, server)) {
                loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }

    private static PlayerAuthInputPacket auth(long tick) {
        var packet = new PlayerAuthInputPacket();
        packet.setTick(tick);
        packet.setPosition(Vector3f.from(-396.039F, 64.66048F, -160.57292F));
        packet.setRotation(Vector3f.from(-28.132812F, 90.15979F, 90.15979F));
        packet.setMotion(Vector2f.ZERO);
        packet.setDelta(Vector3f.ZERO);
        return packet;
    }

    private static EventLoop loop(String name) {
        return new DefaultEventLoop(new DefaultThreadFactory(name));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void seedCarry(CultPlayer player) throws Exception {
        var state = BedrockMovementState.fromPhysicalFeet(new Vec3d(-396.03900146484375, 63.04046630859375,
                -160.5729217529297), new Vec3d(-1.2348907470703125, 0.54685546875, -0.0034332275390625),
                new BedrockInputFrame(10069, 90.15979F, -28.132812F, false, false, true), BedrockCollisionFlags.AIR);
        Class<?> entry = Class.forName("ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState$Entry");
        var constructor = entry.getDeclaredConstructor(BedrockMovementState.class, BedrockMobJumpComponentState.class);
        constructor.setAccessible(true);
        set(player.checkManager.getSimulationProcessor(), SimulationProcessor.class, "profileCarry",
                new BedrockNextTickStates((List) List.of(constructor.newInstance(state, null))));
    }

    private static BedrockMovementState state(CultPlayer player) {
        var carry = (BedrockNextTickStates) player.checkManager.getSimulationProcessor().getCurrentPredictionCommit().carry();
        return carry.profileEntries().getFirst().state();
    }

    private static Map<Object, Object> map(Object owner, String name) throws Exception {
        return map(owner, owner.getClass(), name);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object owner, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(owner);
    }

    private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
