package ac.cult.cultac.packet;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ChannelPacketHandlerTest {
    @Test
    public void configurationBindsInspectionAndWritesToTheOwnerWithoutMovingTheChannel() throws Exception {
        initializeConnectionConfiguration();
        PacketApi api = new PacketApi("owner-test", false);
        Connection connection = Mockito.mock(Connection.class);
        EmbeddedChannel channel = new EmbeddedChannel();
        var owner = new io.netty.util.concurrent.DefaultEventExecutor();
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        channel.pipeline().addLast("packet_handler", new io.netty.channel.ChannelInboundHandlerAdapter());
        var register = PacketApi.class.getDeclaredMethod("registerConnection", Connection.class, io.netty.channel.Channel.class);
        register.setAccessible(true);
        register.invoke(api, connection, channel);
        api.setOwnerResolver(ignored -> owner);
        Mockito.when(connection.getPacketListener()).thenReturn(Mockito.mock(net.minecraft.server.network.ServerConfigurationPacketListenerImpl.class));
        api.registerHandler(net.minecraft.network.protocol.common.ServerboundPongPacket.class, Connection.class, (receiver, packet) -> {
            assertTrue(owner.inEventLoop());
            calls.add("in " + packet.getId());
            return List.of(packet);
        });
        api.registerHandler(ClientboundPingPacket.class, Connection.class, (receiver, packet) -> {
            assertTrue(owner.inEventLoop());
            calls.add("out " + packet.getId());
            return List.of(packet);
        });
        try {
            var ioLoop = channel.eventLoop();
            channel.writeInbound(new net.minecraft.network.protocol.common.ServerboundPongPacket(1));
            channel.writeInbound(new net.minecraft.network.protocol.common.ServerboundPongPacket(2));
            owner.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            channel.runPendingTasks();
            channel.pipeline().writeAndFlush(new ClientboundPingPacket(3));
            owner.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            channel.runPendingTasks();
            assertEquals(List.of("in 1", "in 2", "out 3"), calls);
            org.junit.Assert.assertSame(ioLoop, channel.eventLoop());
            org.junit.Assert.assertSame(owner, api.packetExecutor(channel));
        } finally {
            channel.finishAndReleaseAll();
            owner.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    public void decoderChangeStaysOnIoWhileEncoderChangesFollowPendingPackets() throws Exception {
        initializeConnectionConfiguration();
        PacketApi api = new PacketApi("protocol-test", false);
        Connection connection = Mockito.mock(Connection.class);
        EmbeddedChannel channel = new EmbeddedChannel();
        var owner = new io.netty.util.concurrent.DefaultEventExecutor();
        var releaseOwner = new java.util.concurrent.CountDownLatch(1);
        var ownerBlocked = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        channel.pipeline().addLast("codec", new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                if (message instanceof net.minecraft.network.UnconfiguredPipelineHandler.InboundConfigurationTask task) {
                    task.run(ctx);
                    promise.setSuccess();
                } else if (message instanceof net.minecraft.network.UnconfiguredPipelineHandler.OutboundConfigurationTask task) {
                    task.run(ctx);
                    promise.setSuccess();
                } else {
                    calls.add("packet");
                    ctx.write(message, promise);
                }
            }
        });
        channel.pipeline().addLast("packet_handler", new io.netty.channel.ChannelInboundHandlerAdapter());
        var register = PacketApi.class.getDeclaredMethod("registerConnection", Connection.class, io.netty.channel.Channel.class);
        register.setAccessible(true);
        register.invoke(api, connection, channel);
        api.setOwnerResolver(ignored -> owner);
        try {
            // Login has synchronous encoder changes; it must finish on the physical loop.
            channel.writeInbound(new net.minecraft.network.protocol.common.ServerboundPongPacket(0));
            org.junit.Assert.assertSame(channel.eventLoop(), api.packetExecutor(channel));
            Mockito.when(connection.getPacketListener()).thenReturn(Mockito.mock(net.minecraft.server.network.ServerConfigurationPacketListenerImpl.class));
            owner.execute(() -> {
                ownerBlocked.countDown();
                try { releaseOwner.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(ownerBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var packet = channel.writeAndFlush(new ClientboundPingPacket(1));
            org.junit.Assert.assertSame(owner, api.packetExecutor(channel));
            var inbound = channel.writeAndFlush((net.minecraft.network.UnconfiguredPipelineHandler.InboundConfigurationTask) ctx -> calls.add("decoder"));
            assertTrue(inbound.isSuccess());
            var outbound = channel.writeAndFlush((net.minecraft.network.UnconfiguredPipelineHandler.OutboundConfigurationTask) ctx -> calls.add("encoder"));
            org.junit.Assert.assertFalse(outbound.isDone());
            assertEquals(List.of("decoder"), calls);
            releaseOwner.countDown();
            owner.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            channel.runPendingTasks();
            assertTrue(packet.isSuccess());
            assertTrue(outbound.isSuccess());
            assertEquals(List.of("decoder", "packet", "encoder"), calls);
        } finally {
            releaseOwner.countDown();
            owner.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS).sync();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void cancelledOutboundWriteCompletesPromise() {
        initializeConnectionConfiguration();
        PacketApi api = new PacketApi("promise-test", false);
        Connection connection = Mockito.mock(Connection.class);
        api.registerHandler(ClientboundPingPacket.class, Connection.class,
                (receiver, packet) -> List.of());
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelPacketHandler(api, connection));
        try {
            ChannelPromise promise = channel.newPromise();
            channel.pipeline().write(new ClientboundPingPacket(7), promise);
            channel.runPendingTasks();

            assertTrue(promise.isSuccess());
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void multipleReplacementPacketsKeepTheirOrderInsideBundle() {
        initializeConnectionConfiguration();
        PacketApi api = new PacketApi("bundle-test", false);
        Connection connection = Mockito.mock(Connection.class);
        ClientboundKeepAlivePacket first = new ClientboundKeepAlivePacket(11L);
        ClientboundKeepAlivePacket second = new ClientboundKeepAlivePacket(12L);
        api.registerHandler(ClientboundPingPacket.class, Connection.class,
                (receiver, packet) -> List.of(first, second));
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelPacketHandler(api, connection));
        try {
            assertTrue(channel.writeOutbound(new ClientboundPingPacket(7)));
            Packet<?> outbound = channel.readOutbound();
            assertTrue(outbound instanceof ClientboundBundlePacket);
            assertEquals(List.of(first, second), PacketBundleUtil.flattenOneLevel(outbound));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void oneChildInputBundleKeepsItsBoundary() {
        initializeConnectionConfiguration();
        PacketApi api = new PacketApi("single-bundle-test", false);
        Connection connection = Mockito.mock(Connection.class);
        ClientboundPingPacket child = new ClientboundPingPacket(7);
        AtomicBoolean sawBundleContext = new AtomicBoolean();
        api.registerHandler(ClientboundPingPacket.class, Connection.class, PacketApi.DEFAULT_HANDLER_PRIORITY,
                (PacketContextHandlerFunction<Connection, ClientboundPingPacket>) (context, receiver, packet) -> {
                    sawBundleContext.set(context.insideBundle());
                    return List.of(packet);
                });
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelPacketHandler(api, connection));
        try {
            assertTrue(channel.writeOutbound(new ClientboundBundlePacket(List.of(child))));
            Packet<?> outbound = channel.readOutbound();
            assertTrue(outbound instanceof ClientboundBundlePacket);
            assertEquals(List.of(child), PacketBundleUtil.flattenOneLevel(outbound));
            assertTrue(sawBundleContext.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void cancellingOneChildKeepsTheSurvivingBundleBoundary() {
        initializeConnectionConfiguration();
        PacketApi api = new PacketApi("partial-cancel-bundle-test", false);
        Connection connection = Mockito.mock(Connection.class);
        ClientboundPingPacket cancelled = new ClientboundPingPacket(7);
        ClientboundPingPacket surviving = new ClientboundPingPacket(8);
        api.registerHandler(ClientboundPingPacket.class, Connection.class,
                (receiver, packet) -> packet.getId() == 7 ? List.of() : List.of(packet));
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelPacketHandler(api, connection));
        try {
            assertTrue(channel.writeOutbound(new ClientboundBundlePacket(List.of(cancelled, surviving))));
            Packet<?> outbound = channel.readOutbound();
            assertTrue(outbound instanceof ClientboundBundlePacket);
            assertEquals(List.of(surviving), PacketBundleUtil.flattenOneLevel(outbound));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void cancellingEveryBundleChildCompletesTheWrite() {
        initializeConnectionConfiguration();
        PacketApi api = new PacketApi("full-cancel-bundle-test", false);
        Connection connection = Mockito.mock(Connection.class);
        api.registerHandler(ClientboundPingPacket.class, Connection.class,
                (receiver, packet) -> List.of());
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelPacketHandler(api, connection));
        try {
            ChannelPromise promise = channel.newPromise();
            channel.pipeline().write(new ClientboundBundlePacket(List.of(
                    new ClientboundPingPacket(7),
                    new ClientboundPingPacket(8)
            )), promise);
            channel.runPendingTasks();

            assertTrue(promise.isSuccess());
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void largeBundleFilteringAndExpansionPreservesExactOrder() {
        initializeConnectionConfiguration();
        PacketApi api = new PacketApi("large-bundle-test", false);
        Connection connection = Mockito.mock(Connection.class);
        api.registerHandler(ClientboundPingPacket.class, Connection.class,
                (receiver, packet) -> {
                    int id = packet.getId();
                    if (id % 3 == 0) {
                        return List.of();
                    }
                    if (id % 2 == 0) {
                        return List.of(packet);
                    }
                    return List.of(
                            new ClientboundKeepAlivePacket(id * 10L),
                            new ClientboundKeepAlivePacket(id * 10L + 1L)
                    );
                });

        List<Packet<?>> input = new ArrayList<>();
        for (int id = 0; id < 128; id++) {
            input.add(new ClientboundPingPacket(id));
        }

        EmbeddedChannel channel = new EmbeddedChannel(new ChannelPacketHandler(api, connection));
        try {
            assertTrue(channel.writeOutbound(PacketApi.bundle(input)));
            Packet<?> outbound = channel.readOutbound();
            assertTrue(outbound instanceof ClientboundBundlePacket);

            List<Packet<?>> actual = PacketBundleUtil.flattenOneLevel(outbound);
            int outputIndex = 0;
            for (int id = 0; id < 128; id++) {
                if (id % 3 == 0) {
                    continue;
                }
                if (id % 2 == 0) {
                    assertEquals(id, ((ClientboundPingPacket) actual.get(outputIndex++)).getId());
                } else {
                    assertEquals(id * 10L,
                            ((ClientboundKeepAlivePacket) actual.get(outputIndex++)).getId());
                    assertEquals(id * 10L + 1L,
                            ((ClientboundKeepAlivePacket) actual.get(outputIndex++)).getId());
                }
            }
            assertEquals(outputIndex, actual.size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void initializeConnectionConfiguration() {
        OfflineCultTestBootstrap.installConfig();
        try {
            Class<?> globalType = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            Object global = globalType.getDeclaredMethod("get").invoke(null);
            var miscField = globalType.getField("misc");
            if (miscField.get(global) == null) {
                Class<?> miscType = Class.forName(
                        "io.papermc.paper.configuration.GlobalConfiguration$Misc");
                miscField.set(global, miscType.getConstructor(globalType).newInstance(global));
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to initialize Paper connection config", exception);
        }
    }
}
