package ac.cult.cultac.platform.velocity.bridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.codec.ClientPacketCodecs;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class BridgeRoutesTest {
    @Test
    void packetCatalogsUseTheBridgeChannel() {
        assertEquals(BridgeProtocol.CHANNEL, ClientboundPackets.BRIDGE_CHANNEL);
    }

    @Test
    void bridgeHooksRegisterOnTheProxyRuntime() {
        var dispatcher = new PacketDispatcher(ProtocolRuntime.create(
                ProtocolData.load(ProtocolVersion.V26_3), ClientPacketCodecs.connectionCatalog(), 32767));
        var service = new BridgeService(
                mock(ProxyServer.class),
                new Object(),
                player -> null,
                channel -> null,
                Logger.getLogger("BridgeRoutesTest"));
        service.registerStripRoutes(dispatcher);
        service.registerSessionRoutes(dispatcher);

        var serverbound = dispatcher.get(ServerboundPackets.CUSTOM_PAYLOAD).receive();
        assertFalse(serverbound.early().isEmpty());
        assertFalse(serverbound.connection().isEmpty());
        assertFalse(dispatcher.get(ClientboundPackets.CUSTOM_PAYLOAD).send().isEmpty());
        assertFalse(dispatcher.get(ClientboundPackets.LOGIN).send().isEmpty());
        assertFalse(
                dispatcher.get(ClientboundPackets.START_CONFIGURATION).send().isEmpty());
    }

    @Test
    void disabledBridgeOnlyStripsClientPayloads() {
        var dispatcher = new PacketDispatcher(ProtocolRuntime.create(
                ProtocolData.load(ProtocolVersion.V26_3), ClientPacketCodecs.connectionCatalog(), 32767));
        var service = new BridgeService(
                mock(ProxyServer.class),
                new Object(),
                player -> null,
                channel -> null,
                Logger.getLogger("BridgeRoutesTest"));
        service.registerStripRoutes(dispatcher);

        assertFalse(dispatcher
                .get(ServerboundPackets.CUSTOM_PAYLOAD)
                .receive()
                .early()
                .isEmpty());
        for (var type : new PacketType<?>[] {ClientboundPackets.CUSTOM_PAYLOAD, ClientboundPackets.LOGIN}) {
            var route = dispatcher.get(type);
            assertTrue(route == null || route.send() == null, type::toString);
        }
    }

    @Test
    void sessionRoutesRunForConnectionsWithoutAPlayer() {
        var dispatcher = new PacketDispatcher(ProtocolRuntime.create(
                ProtocolData.load(ProtocolVersion.V26_3), ClientPacketCodecs.connectionCatalog(), 32767));
        var service = new BridgeService(
                mock(ProxyServer.class),
                new Object(),
                player -> null,
                channel -> null,
                Logger.getLogger("BridgeRoutesTest"));
        service.registerSessionRoutes(dispatcher);

        var user = mock(User.class, RETURNS_DEEP_STUBS);
        when(user.getPacketExecutor().inEventLoop()).thenReturn(true);
        when(user.getCultPlayer()).thenReturn(null);
        when(user.getUUID()).thenReturn(UUID.randomUUID());
        @SuppressWarnings("unchecked")
        PacketSendEvent<ClientboundCustomPayload> event = mock(PacketSendEvent.class);
        when(event.getUser()).thenReturn(user);
        when(event.getOriginalPacket())
                .thenReturn(new ClientboundCustomPayload(BridgeProtocol.CHANNEL, new byte[] {1}));

        dispatcher.send(event, dispatcher.get(ClientboundPackets.CUSTOM_PAYLOAD).send());

        verify(event).cancelFinally();
    }

    @Test
    void strippedPayloadHoldsWithoutAPlayer() {
        assertStripHolds(null);
    }

    @Test
    void strippedPayloadHoldsForAPlayer() {
        assertStripHolds(mock(CultPlayer.class));
    }

    private static void assertStripHolds(CultPlayer player) {
        var dispatcher = new PacketDispatcher(ProtocolRuntime.create(
                ProtocolData.load(ProtocolVersion.V26_3), ClientPacketCodecs.connectionCatalog(), 32767));
        var service = new BridgeService(
                mock(ProxyServer.class),
                new Object(),
                ignored -> null,
                channel -> null,
                Logger.getLogger("BridgeRoutesTest"));
        service.registerStripRoutes(dispatcher);
        dispatcher.register(routes -> {
            routes.earlyReceive(
                    ServerboundPackets.CUSTOM_PAYLOAD,
                    PacketListenerPriority.LOW,
                    (event, cult, packet) -> event.setCancelled(false));
            routes.earlyReceive(
                    ServerboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.NORMAL, (event, cult, packet) -> {
                        throw new IllegalStateException("later handler");
                    });
            routes.receiveConnection(
                    ServerboundPackets.CUSTOM_PAYLOAD,
                    PacketListenerPriority.LOW,
                    (event, packet) -> event.setCancelled(false));
            routes.receiveConnection(
                    ServerboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.NORMAL, (event, packet) -> {
                        throw new IllegalStateException("later handler");
                    });
        });
        var user = mock(User.class, RETURNS_DEEP_STUBS);
        when(user.getPacketExecutor().inEventLoop()).thenReturn(true);
        when(user.getBedrockBridgeConnection()).thenReturn(null);
        when(user.getCultPlayer()).thenReturn(player);

        var forged = new PacketReceiveEvent<>(
                user,
                ConnectionPhase.PLAY,
                ServerboundPackets.CUSTOM_PAYLOAD,
                new ServerboundCustomPayload(BridgeProtocol.CHANNEL, new byte[] {0x2C}));
        dispatcher.receive(
                forged, dispatcher.get(ServerboundPackets.CUSTOM_PAYLOAD).receive());
        assertTrue(forged.isCancelled());

        var brand = new PacketReceiveEvent<>(
                user,
                ConnectionPhase.PLAY,
                ServerboundPackets.CUSTOM_PAYLOAD,
                new ServerboundCustomPayload("minecraft:brand", new byte[] {1}));
        dispatcher.receive(
                brand, dispatcher.get(ServerboundPackets.CUSTOM_PAYLOAD).receive());
        assertFalse(brand.isCancelled());
    }
}
