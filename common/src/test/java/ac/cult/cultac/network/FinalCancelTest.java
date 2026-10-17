package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
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
}
