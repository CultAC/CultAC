package com.noxcrew.packet;

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
