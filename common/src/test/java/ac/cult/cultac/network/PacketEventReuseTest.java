package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.event.PacketEvent;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PacketEventReuseTest {
    @Test
    void routesWithOutsideHandlersGetEventsNoLaterPacketReuses() {
        OfflineCultTestBootstrap.installConfig();
        var dispatcher = new PacketDispatcher(
                CultAPI.INSTANCE.getNetworkManager().dispatcher().runtime());
        List<PacketEvent<?>> ownPings = new ArrayList<>();
        List<PacketEvent<?>> ownKeepAlives = new ArrayList<>();
        List<PacketEvent<?>> keptSends = new ArrayList<>();
        List<PacketEvent<?>> keptReceives = new ArrayList<>();
        dispatcher.registerOwn(routes -> {
            routes.send(
                    ClientboundPackets.PING,
                    PacketListenerPriority.NORMAL,
                    (event, player, packet) -> ownPings.add(event));
            routes.send(ClientboundPackets.KEEP_ALIVE, PacketListenerPriority.NORMAL, (event, player, packet) -> {});
            routes.receive(
                    ServerboundPackets.KEEP_ALIVE,
                    PacketListenerPriority.NORMAL,
                    (event, player, packet) -> ownKeepAlives.add(event));
        });
        dispatcher.register(routes -> {
            routes.send(
                    ClientboundPackets.KEEP_ALIVE,
                    PacketListenerPriority.NORMAL,
                    (event, player, packet) -> keptSends.add(event));
            routes.receive(
                    ServerboundPackets.PONG,
                    PacketListenerPriority.NORMAL,
                    (event, player, packet) -> keptReceives.add(event));
        });
        assertFalse(dispatcher.get(ClientboundPackets.KEEP_ALIVE).own());

        var channel = new EmbeddedChannel();
        var connection = new CultConnection(channel, dispatcher, ignored -> new PacketOwner(channel.eventLoop(), null));
        for (var direction : PacketDirection.values()) connection.phase(direction, ConnectionPhase.PLAY);
        var user = new User(new User.Profile(UUID.randomUUID(), ".Reuse_Test"), connection);
        CultPlayer player = new CultPlayer(user);
        synchronized (connection) {
            connection.player(player);
        }
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        CultDecoder.install(connection);
        CultEncoder.install(connection);
        var data = dispatcher.runtime().data();
        var clientbound = data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
        var serverbound = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND);
        try {
            for (int i = 0; i < 3; i++) {
                write(channel, frame(clientbound.id("minecraft:keep_alive")).writeLong(10 + i));
                write(channel, frame(clientbound.id("minecraft:ping")).writeInt(20 + i));
                read(channel, frame(serverbound.id("minecraft:pong")).writeInt(30 + i));
                read(channel, frame(serverbound.id("minecraft:keep_alive")).writeLong(40 + i));
            }

            assertEquals(3, ownPings.size());
            assertSame(ownPings.get(0), ownPings.get(1));
            assertSame(ownPings.get(1), ownPings.get(2));
            assertEquals(3, ownKeepAlives.size());
            assertSame(ownKeepAlives.get(0), ownKeepAlives.get(1));
            assertSame(ownKeepAlives.get(1), ownKeepAlives.get(2));

            assertEquals(3, keptSends.size());
            assertEquals(3, keptReceives.size());
            for (int i = 0; i < 3; i++) {
                var sent = keptSends.get(i);
                assertNotSame(ownPings.getFirst(), sent);
                for (int j = 0; j < i; j++) assertNotSame(keptSends.get(j), sent);
                assertSame(user, sent.getUser());
                assertEquals(ConnectionPhase.PLAY, sent.getPhase());
                assertSame(ClientboundPackets.KEEP_ALIVE, sent.getPacketType());
                assertEquals(new ClientboundKeepAlive(10 + i), sent.getPacket());
                assertSame(sent.getPacket(), sent.getOriginalPacket());
                assertFalse(sent.isCancelled());

                var received = keptReceives.get(i);
                assertNotSame(ownKeepAlives.getFirst(), received);
                for (int j = 0; j < i; j++) assertNotSame(keptReceives.get(j), received);
                assertSame(user, received.getUser());
                assertEquals(ConnectionPhase.PLAY, received.getPhase());
                assertSame(ServerboundPackets.PONG, received.getPacketType());
                assertEquals(new ServerboundPong(30 + i), received.getPacket());
                assertSame(received.getPacket(), received.getOriginalPacket());
                assertFalse(received.isCancelled());
            }
            assertNull(ownPings.getFirst().getPacket());
            assertNull(ownKeepAlives.getFirst().getPacket());
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static ByteBuf frame(int id) {
        ByteBuf frame = Unpooled.buffer();
        Wire.writeVarInt(frame, id);
        return frame;
    }

    private static void write(EmbeddedChannel channel, ByteBuf frame) {
        channel.writeAndFlush(frame);
        for (Object written; (written = channel.readOutbound()) != null; ) ReferenceCountUtil.release(written);
    }

    private static void read(EmbeddedChannel channel, ByteBuf frame) {
        channel.writeInbound(frame);
        for (Object read; (read = channel.readInbound()) != null; ) ReferenceCountUtil.release(read);
    }
}
