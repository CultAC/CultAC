package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FinalCancelTest {
    private static final String CHANNEL = "cultac:final-cancel-test";

    private static ByteBuf payload(RecordReceiveFixture fixture, String channel, int length) {
        var frame = fixture.id(ServerboundPackets.CUSTOM_PAYLOAD, "custom_payload");
        Wire.writeString(frame, channel, 32767);
        return frame.writeZero(length);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failingLaterHandlersCannotForwardAFinallyCancelledFrame(boolean withPlayer) {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, withPlayer)) {
            fixture.registrar.register(routes -> {
                routes.earlyReceive(
                        ServerboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.LOWEST, (event, player, packet) -> {
                            if (CHANNEL.equals(packet.channel())) event.cancelFinally();
                        });
                routes.earlyReceive(
                        ServerboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                            throw new IllegalStateException("later handler");
                        });
                routes.receiveConnection(
                        ServerboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.LOWEST, (event, packet) -> {
                            if (CHANNEL.equals(packet.channel())) event.cancelFinally();
                        });
                routes.receiveConnection(
                        ServerboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.NORMAL, (event, packet) -> {
                            throw new IllegalStateException("later handler");
                        });
            });

            var forged = payload(fixture, CHANNEL, 4);
            assertFalse(fixture.channel.writeInbound(forged));
            assertNull(fixture.channel.readInbound());
            assertEquals(0, forged.refCnt());

            fixture.forward(payload(fixture, "minecraft:brand", 4));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void undecodableFramesOnADroppedChannelAreNotForwarded(boolean dropped) {
        String channel = dropped ? ClientboundPackets.BRIDGE_CHANNEL : "cultac:forwarded-test";
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, false)) {
            fixture.registrar.register(routes -> routes.receiveConnection(
                    ServerboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.LOWEST, (event, packet) -> {}));

            var oversized = payload(fixture, channel, 32768);
            if (dropped) {
                assertFalse(fixture.channel.writeInbound(oversized));
                assertNull(fixture.channel.readInbound());
                assertEquals(0, oversized.refCnt());
            } else {
                fixture.forward(oversized);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observeOnlyForwardsWhatTheClientSent(boolean observeOnly) throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
                var ignored = RecordReceiveFixture.observeOnly(observeOnly)) {
            fixture.registrar.register(routes -> {
                routes.receive(
                        ServerboundPackets.CUSTOM_PAYLOAD,
                        PacketListenerPriority.NORMAL,
                        (event, player, packet) -> event.setCancelled(true));
                routes.receive(
                        ServerboundPackets.MOVE_PLAYER,
                        PacketListenerPriority.NORMAL,
                        (event, player, packet) -> event.replace(packet.withPosition(9, 70, 9, true)));
            });

            var move = fixture.encode(
                    ServerboundPackets.MOVE_PLAYER,
                    new ServerboundMovePlayer(1, 64, 1, 0, 0, false, false, true, false));
            for (var sent : new ByteBuf[] {payload(fixture, CHANNEL, 4), move}) {
                String bytes = ByteBufUtil.hexDump(sent);
                fixture.channel.writeInbound(sent);
                ByteBuf received = fixture.channel.readInbound();
                if (observeOnly) assertEquals(bytes, ByteBufUtil.hexDump(received));
                else if (sent == move) assertNotEquals(bytes, ByteBufUtil.hexDump(received));
                else assertNull(received);
                if (received != null) received.release();
            }
            assertEquals(0, fixture.channel.inboundMessages().size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observeOnlyWithholdsAClientPayloadOnTheBridgeChannel(boolean observeOnly) throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
                var ignored = RecordReceiveFixture.observeOnly(observeOnly)) {
            fixture.registrar.register(routes -> routes.receiveConnection(
                    ServerboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.LOWEST, (event, packet) -> {
                        if (ClientboundPackets.BRIDGE_CHANNEL.equals(packet.channel())) event.cancelFinally();
                    }));

            for (int length : new int[] {4, 32768}) {
                var sent = payload(fixture, ClientboundPackets.BRIDGE_CHANNEL, length);
                assertFalse(fixture.channel.writeInbound(sent));
                assertNull(fixture.channel.readInbound());
                assertEquals(0, sent.refCnt());
            }
            fixture.forward(payload(fixture, "minecraft:brand", 4));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observeOnlyWithholdsOnlyThePongThatAnswersCultsPing(boolean observeOnly) throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
                var ignored = RecordReceiveFixture.observeOnly(observeOnly)) {
            fixture.registrar.register(
                    routes -> routes.registerReceiveListener(PacketListenerPriority.LOWEST, new PacketPingListener()));
            var method = CultPlayer.class.getDeclaredMethod("createTrackedTransaction", int.class, int.class);
            method.setAccessible(true);
            var ping = (CultPlayer.TrackedTransaction) method.invoke(fixture.player, CultPlayer.PING_LEADING, 0);
            fixture.player.markTrackedTransactionPacketSent(ping);

            var own = fixture.pong(ping.id());
            assertFalse(fixture.channel.writeInbound(own));
            assertEquals(0, own.refCnt());
            fixture.forward(fixture.pong(ping.id() + 1));
        }
    }
}
