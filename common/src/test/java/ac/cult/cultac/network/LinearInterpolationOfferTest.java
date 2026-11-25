package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.events.packets.listeners.PacketServerChannels;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** CultAC is the only source of the linear interpolation offer. */
class LinearInterpolationOfferTest {
    @Test
    void aForeignRegisterLosesTheLinearChannelsAndKeepsTheRestInOrder() throws Exception {
        for (String channel : new String[] {"minecraft:register", "minecraft:unregister"}) {
            try (var f = new JavaTickFixture()) {
                var event = filtered(
                        f, channel, "velocity:a\0antistutter:linear_3\0fabric:b\0interpolationfix:linear_3\0lunar:c");
                assertFalse(event.isCancelled());
                assertEquals(channel, event.getPacket().channel());
                assertEquals(
                        "velocity:a\0fabric:b\0lunar:c",
                        new String(event.getPacket().data(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void aForeignFrameNamingOnlyTheLinearChannelIsDropped() throws Exception {
        for (String channel : new String[] {"minecraft:register", "minecraft:unregister"}) {
            try (var f = new JavaTickFixture()) {
                assertTrue(filtered(f, channel, "antistutter:linear_3").isCancelled());
                assertTrue(filtered(f, channel, "antistutter:linear_3\0").isCancelled());
            }
        }
    }

    @Test
    void otherFramesPassUntouched() throws Exception {
        try (var f = new JavaTickFixture()) {
            var register = filtered(f, "minecraft:register", "velocity:a\0fabric:b");
            assertFalse(register.isCancelled());
            assertFalse(register.isReplaced());
            var other = filtered(f, "lunar:apollo", "antistutter:linear_3");
            assertFalse(other.isCancelled());
            assertSame(other.getOriginalPacket(), other.getPacket());
        }
    }

    private static PacketSendEvent<ClientboundCustomPayload> filtered(JavaTickFixture f, String channel, String list) {
        var payload = new ClientboundCustomPayload(channel, list.getBytes(StandardCharsets.UTF_8));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.CUSTOM_PAYLOAD, payload, false);
        PacketServerChannels.onCustomPayload(event, payload);
        return event;
    }
}
