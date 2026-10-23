package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacketRoutesTest {
    @Test
    void everyBoundIdOfARoutedFamilyReachesItsEntryInEveryDeclaredPhase() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            ProtocolData data = ProtocolData.load(version);
            PacketDispatcher routes = new PacketDispatcher(ProtocolRuntime.create(data));
            routes.register(batch -> batch.receive(
                    ServerboundPackets.MOVE_PLAYER,
                    ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                    (event, player, packet) -> {}));
            routes.register(batch -> batch.receive(
                    ServerboundPackets.KEEP_ALIVE,
                    ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                    (event, player, packet) -> {}));
            for (PacketType<?> type : List.of(ServerboundPackets.MOVE_PLAYER, ServerboundPackets.KEEP_ALIVE)) {
                for (String name : type.wireNames(data)) {
                    for (ConnectionPhase phase : type.phases()) {
                        var entry = routes.get(
                                PacketDirection.SERVERBOUND,
                                phase,
                                data.packets(phase, PacketDirection.SERVERBOUND).id(name));
                        assertSame(type, entry.type(), version + "/" + phase + "/" + name);
                        assertNotNull(entry.receive());
                    }
                }
            }
            int pong = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND)
                    .id("minecraft:pong");
            assertNull(routes.get(PacketDirection.SERVERBOUND, ConnectionPhase.PLAY, pong));
            assertNull(routes.get(PacketDirection.SERVERBOUND, ConnectionPhase.PLAY, -1));
            assertNull(routes.get(PacketDirection.SERVERBOUND, ConnectionPhase.PLAY, Integer.MAX_VALUE));
        }
    }

    /** Decoders reuse the route their predicate found for a type, and take the route's type as the packet's. */
    @Test
    void aTypeLookupReturnsTheEntryEveryBoundIdOfTheTypeReaches() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            ProtocolData data = ProtocolData.load(version);
            ProtocolRuntime runtime = ProtocolRuntime.create(data);
            PacketDispatcher routes = new PacketDispatcher(runtime);
            routes.register(batch -> {
                batch.receive(ServerboundPackets.MOVE_PLAYER, PacketListenerPriority.NORMAL, (e, p, packet) -> {});
                batch.send(ClientboundPackets.PING, PacketListenerPriority.NORMAL, (e, p, packet) -> {});
            });
            int routed = 0;
            for (ConnectionPhase phase : ConnectionPhase.values()) {
                for (PacketDirection direction : PacketDirection.values()) {
                    for (int id = 0; id < data.packets(phase, direction).size(); id++) {
                        var binding = runtime.binding(phase, direction, id);
                        var byId = routes.get(direction, phase, id);
                        if (binding == null) {
                            assertNull(byId);
                            continue;
                        }
                        assertSame(byId, routes.get(binding.type()), version + "/" + phase + "/" + id);
                        if (byId != null) {
                            assertSame(binding.type(), byId.type());
                            routed++;
                        }
                    }
                }
            }
            assertTrue(routed > 2, version.toString());
        }
    }

    @Test
    void structuralFamiliesKeepACallbackFreeRouteWhileOrdinaryFamiliesClear() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.V26_3);
        PacketDispatcher routes = new PacketDispatcher(ProtocolRuntime.create(data));
        int intention = data.packets(ConnectionPhase.HANDSHAKE, PacketDirection.SERVERBOUND)
                .id("minecraft:intention");
        assertEquals(
                new PacketDispatcher.Route(ServerboundPackets.INTENTION, null, null),
                routes.get(PacketDirection.SERVERBOUND, ConnectionPhase.HANDSHAKE, intention));

        int bundle =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:bundle_delimiter");
        PacketDispatcher.Route structural = new PacketDispatcher.Route(ClientboundPackets.BUNDLE_DELIMITER, null, null);
        assertEquals(structural, routes.get(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY, bundle));
        routes.register(batch -> batch.send(
                ClientboundPackets.BUNDLE_DELIMITER,
                ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                (event, player, packet) -> {}));
        assertNotNull(routes.get(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY, bundle)
                .send());
        routes.clear();
        assertEquals(structural, routes.get(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY, bundle));

        int ping =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:ping");
        routes.register(batch -> batch.send(
                ClientboundPackets.PING,
                ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                (event, player, packet) -> {}));
        assertSame(
                ClientboundPackets.PING,
                routes.get(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY, ping)
                        .type());
        routes.clear();
        assertNull(routes.get(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY, ping));
    }

    @Test
    void familiesOutsideTheRuntimeCatalogAreRejected() {
        PacketDispatcher routes =
                new PacketDispatcher(ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3)));
        var source = ServerboundPackets.PONG;
        var foreign = new PacketType<>(
                source.key(),
                source.recordClass(),
                source.direction(),
                source.phases(),
                source.wireNames(),
                source.since(),
                source.codec());
        assertThrows(
                IllegalArgumentException.class,
                () -> routes.register(batch -> batch.receive(
                        foreign,
                        ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                        (event, player, packet) -> {})));
    }

    @Test
    void clearingCallbacksPreservesPreparationAndDirectionalConfigurationCompletion() {
        var runtime = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
        var dispatcher = new PacketDispatcher(runtime);
        dispatcher.register(batch -> {
            batch.receive(
                    ServerboundPackets.FINISH_CONFIGURATION,
                    ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                    (e, p, r) -> fail("Cleared receive callback"));
            batch.send(
                    ClientboundPackets.FINISH_CONFIGURATION,
                    ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                    (e, p, r) -> fail("Cleared send callback"));
        });
        dispatcher.clear();
        for (var direction : PacketDirection.values()) {
            var channel = new io.netty.channel.embedded.EmbeddedChannel();
            var connection = new CultConnection(channel, dispatcher, ignored -> null);
            var prepared = new java.util.concurrent.atomic.AtomicInteger();
            connection.initializer(ignored -> prepared.incrementAndGet());
            for (var side : PacketDirection.values()) connection.phase(side, ConnectionPhase.CONFIGURATION);
            channel.pipeline().addLast("decoder", new io.netty.channel.ChannelInboundHandlerAdapter());
            channel.pipeline().addLast("encoder", new io.netty.channel.ChannelOutboundHandlerAdapter());
            ac.cult.cultac.protocol.netty.CultDecoder.install(connection);
            ac.cult.cultac.protocol.netty.CultEncoder.install(connection);
            var frame = io.netty.buffer.Unpooled.buffer();
            ac.cult.cultac.protocol.wire.Wire.writeVarInt(
                    frame,
                    runtime.data()
                            .packets(ConnectionPhase.CONFIGURATION, direction)
                            .id("minecraft:finish_configuration"));
            try {
                Object forwarded;
                if (direction == PacketDirection.SERVERBOUND) {
                    channel.writeInbound(frame);
                    forwarded = channel.readInbound();
                } else {
                    channel.writeOutbound(frame);
                    forwarded = channel.readOutbound();
                }
                assertSame(frame, forwarded);
                frame.release();
                assertEquals(1, prepared.get());
                assertEquals(ConnectionPhase.PLAY, connection.phase(direction));
                var opposite = direction == PacketDirection.SERVERBOUND
                        ? PacketDirection.CLIENTBOUND
                        : PacketDirection.SERVERBOUND;
                assertEquals(ConnectionPhase.CONFIGURATION, connection.phase(opposite));
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }
}
