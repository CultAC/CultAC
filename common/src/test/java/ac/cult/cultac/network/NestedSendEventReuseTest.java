package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Own-route events reused across nested expansions start each dispatch as a new event would. */
class NestedSendEventReuseTest {
    @Test
    void nestedOwnRouteEventsStartFreshAndKeepTheWireOrder() {
        OfflineCultTestBootstrap.installConfig();
        var dispatcher = new PacketDispatcher(
                CultAPI.INSTANCE.getNetworkManager().dispatcher().runtime());
        List<String> seen = new ArrayList<>();
        List<PacketSendEvent<?>> outer = new ArrayList<>(), nested = new ArrayList<>();
        dispatcher.registerOwn(routes -> {
            routes.send(ClientboundPackets.KEEP_ALIVE, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                outer.add(event);
                seen.add(state(event) + " keep_alive " + packet.id());
                int id = (int) packet.id();
                event.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(id * 10 + 1), false));
                event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(id * 10 + 2), false));
                if (id % 2 == 0) event.bundle();
            });
            routes.send(ClientboundPackets.PING, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                nested.add(event);
                seen.add(state(event) + " ping " + packet.id());
                // The family is already dispatching, so this write is not dispatched again.
                if (packet.id() % 10 == 2) event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(7), false));
            });
        });
        var channel = new EmbeddedChannel();
        var connection = new CultConnection(channel, dispatcher, ignored -> new PacketOwner(channel.eventLoop(), null));
        for (var direction : PacketDirection.values()) connection.phase(direction, ConnectionPhase.PLAY);
        var user = new User(new User.Profile(UUID.randomUUID(), ".Nested_Reuse_Test"), connection);
        CultPlayer player = new CultPlayer(user);
        synchronized (connection) {
            connection.player(player);
        }
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        CultDecoder.install(connection);
        CultEncoder.install(connection);
        var clientbound = dispatcher.runtime().data().packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
        List<String> wire = new ArrayList<>();
        try {
            for (int i = 1; i <= 4; i++) {
                ByteBuf frame = Unpooled.buffer();
                Wire.writeVarInt(frame, clientbound.id("minecraft:keep_alive"));
                channel.writeAndFlush(frame.writeLong(i));
                for (Object out; (out = channel.readOutbound()) != null; ) {
                    wire.add(ByteBufUtil.hexDump((ByteBuf) out));
                    ((ByteBuf) out).release();
                }
            }
            List<String> expected = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                expected.add("fresh keep_alive " + i);
                expected.add("fresh authored ping " + (i * 10 + 1));
                expected.add("fresh authored ping " + (i * 10 + 2));
            }
            assertEquals(expected, seen);
            int keepAlive = clientbound.id("minecraft:keep_alive"), ping = clientbound.id("minecraft:ping");
            List<String> expectedWire = new ArrayList<>();
            String delimiter = hex(Unpooled.buffer().writeByte(clientbound.id("minecraft:bundle_delimiter")));
            // A keep-alive stays outside the bundle that its writes before and after open.
            for (int i = 1; i <= 4; i++) {
                expectedWire.add(delimiter);
                expectedWire.add(hex(Unpooled.buffer().writeByte(ping).writeInt(i * 10 + 1)));
                expectedWire.add(delimiter);
                expectedWire.add(hex(Unpooled.buffer().writeByte(keepAlive).writeLong(i)));
                expectedWire.add(delimiter);
                expectedWire.add(hex(Unpooled.buffer().writeByte(ping).writeInt(i * 10 + 2)));
                expectedWire.add(hex(Unpooled.buffer().writeByte(ping).writeInt(7)));
                expectedWire.add(delimiter);
            }
            assertEquals(expectedWire, wire);
            Set<PacketSendEvent<?>> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            distinct.addAll(outer);
            distinct.addAll(nested);
            // Each nested dispatch takes the event its parent did not.
            assertEquals(2, distinct.size());
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    /** "fresh" when the event holds nothing a previous dispatch left. */
    private static String state(PacketSendEvent<?> event) {
        boolean fresh = !event.isCancelled()
                && event.writesBefore().isEmpty()
                && event.writesAfter().isEmpty()
                && event.tasksAfter().isEmpty()
                && !event.isBundleRequested()
                && event.leadingPing() == null
                && event.trackedPing() == 0
                && event.receiptProof() == 0
                && event.getPacket() == event.getOriginalPacket()
                && !event.isReplaced();
        return (fresh ? "fresh" : "stale") + (event.isAuthored() ? " authored" : "");
    }

    private static String hex(ByteBuf frame) {
        try {
            return ByteBufUtil.hexDump(frame);
        } finally {
            frame.release();
        }
    }
}
