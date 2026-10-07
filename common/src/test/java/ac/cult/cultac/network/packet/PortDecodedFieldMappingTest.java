package ac.cult.cultac.network.packet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import ac.cult.cultac.checks.impl.chat.ChatB;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommandSigned;
import ac.cult.cultac.protocol.testing.CodecFixture;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.Unpooled;
import java.util.OptionalInt;
import org.junit.Test;

public final class PortDecodedFieldMappingTest {
    @Test
    public void chatCommandHandlersBindSignedAndUnsignedRecordFamiliesCorrectly() throws Exception {
        assertNotNull(ChatB.class.getDeclaredMethod(
                "onChatCommandUnsigned", PacketReceiveEvent.class, CultPlayer.class, ServerboundChatCommand.class));
        assertNotNull(ChatB.class.getDeclaredMethod(
                "onChatCommand", PacketReceiveEvent.class, CultPlayer.class, ServerboundChatCommandSigned.class));
    }

    @Test
    public void spectatorSentinelConversionPreservesPacketEventsIntegerContract() {
        var data = ProtocolData.load(ProtocolVersion.V26_3);
        var codec = new CodecFixture(ProtocolRuntime.create(data));
        codec.phase(ConnectionPhase.PLAY);
        int id = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND).id("minecraft:spectator_action");
        var bytes = Unpooled.buffer();
        try {
            Wire.writeVarInt(bytes, 43);
            assertEquals(
                    42,
                    codec.read(ServerboundPackets.SPECTATOR_ACTION, id, bytes)
                            .target()
                            .orElse(0));
            bytes.clear();
            Wire.writeVarInt(bytes, 0);
            assertEquals(
                    0,
                    codec.read(ServerboundPackets.SPECTATOR_ACTION, id, bytes)
                            .target()
                            .orElse(0));
        } finally {
            bytes.release();
        }
    }

    @Test
    public void transactionAcceptanceDefaultsToFalseOnEachReceiveEvent() {
        var event = new PacketReceiveEvent<>(
                null,
                ConnectionPhase.PLAY,
                ServerboundPackets.SPECTATOR_ACTION,
                new ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction(OptionalInt.empty()));
        assertFalse(event.isAcceptedTransactionResponse());
    }
}
