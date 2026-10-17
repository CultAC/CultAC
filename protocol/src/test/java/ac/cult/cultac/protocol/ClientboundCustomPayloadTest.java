package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.codec.connection.ClientboundCustomPayloadCodec;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import ac.cult.cultac.protocol.testing.CodecFixture;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClientboundCustomPayloadTest {
    private static final ProtocolData DATA = ProtocolData.load(ProtocolVersion.V26_3);

    @Test
    void configurationAndPlayRoundTripChannelAndRawBytes() {
        CodecFixture connection = new CodecFixture(ProtocolRuntime.create(DATA));
        for (ConnectionPhase phase : new ConnectionPhase[] {ConnectionPhase.CONFIGURATION, ConnectionPhase.PLAY}) {
            connection.phase(phase);
            int id = DATA.packets(phase, PacketDirection.CLIENTBOUND).id("minecraft:custom_payload");
            assertSame(ClientboundPackets.CUSTOM_PAYLOAD, connection.typeOf(PacketDirection.CLIENTBOUND, id));
            var packet = new ClientboundCustomPayload("cultac:bridge", new byte[] {1, 0, (byte) 0xFF});
            ByteBuf bytes = Unpooled.buffer();
            try {
                connection.write(packet, bytes);
                assertEquals(id, Wire.readVarInt(bytes));
                var decoded = connection.read(ClientboundPackets.CUSTOM_PAYLOAD, id, bytes);
                assertEquals(packet, decoded);
                assertArrayEquals(new byte[] {1, 0, (byte) 0xFF}, decoded.data());
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    void onlyRetainedChannelsAreDecoded() {
        var codec = new ClientboundCustomPayloadCodec(Set.of("cultac:bridge"));
        ByteBuf frame = Unpooled.buffer();
        try {
            frame.writeByte(0x18);
            Wire.writeString(frame, "cultac:bridge", 32767);
            frame.writeByte(1);
            assertFalse(codec.forwardsUndecoded(frame, 1));

            frame.clear().writeByte(0x18);
            Wire.writeString(frame, "minecraft:brand", 32767);
            assertTrue(codec.forwardsUndecoded(frame, 1));

            frame.clear().writeByte(0x18);
            Wire.writeString(frame, "cultac:bridgf", 32767);
            assertTrue(codec.forwardsUndecoded(frame, 1));

            frame.clear().writeByte(0x18).writeByte(40).writeByte('c');
            assertFalse(codec.forwardsUndecoded(frame, 1));
            assertEquals(0, frame.readerIndex());
        } finally {
            frame.release();
        }
    }

    @Test
    void otherChannelsDecodeWithoutTheirBytesAndCannotBeWritten() {
        CodecFixture connection = new CodecFixture(ProtocolRuntime.create(DATA));
        connection.phase(ConnectionPhase.PLAY);
        int id = DATA.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:custom_payload");
        ByteBuf bytes = Unpooled.buffer();
        try {
            Wire.writeString(bytes, "minecraft:brand", 32767);
            bytes.writeBytes(new byte[] {5, 'P', 'a', 'p', 'e', 'r'});
            var decoded = connection.read(ClientboundPackets.CUSTOM_PAYLOAD, id, bytes);
            assertEquals("minecraft:brand", decoded.channel());
            assertSame(ClientboundCustomPayloadCodec.SKIPPED, decoded.data());

            ByteBuf output = Unpooled.buffer();
            try {
                assertThrows(IllegalStateException.class, () -> connection.write(decoded, output));
            } finally {
                output.release();
            }
        } finally {
            bytes.release();
        }
    }

    @Test
    void emptyPayloadOnAnyChannelIsStillWritable() {
        CodecFixture connection = new CodecFixture(ProtocolRuntime.create(DATA));
        connection.phase(ConnectionPhase.PLAY);
        int id = DATA.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:custom_payload");
        var packet = new ClientboundCustomPayload("minecraft:brand", new byte[0]);
        ByteBuf bytes = Unpooled.buffer();
        try {
            connection.write(packet, bytes);
            assertEquals(id, Wire.readVarInt(bytes));
            assertEquals(
                    "minecraft:brand",
                    connection
                            .read(ClientboundPackets.CUSTOM_PAYLOAD, id, bytes)
                            .channel());
        } finally {
            bytes.release();
        }
    }

    @Test
    void oversizedPayloadOnAnotherChannelIsStillMalformed() {
        CodecFixture connection = new CodecFixture(ProtocolRuntime.create(DATA));
        connection.phase(ConnectionPhase.PLAY);
        int id = DATA.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:custom_payload");
        ByteBuf bytes = Unpooled.buffer();
        try {
            Wire.writeString(bytes, "minecraft:brand", 32767);
            bytes.writeZero(1048577);
            assertThrows(
                    MalformedPacketException.class,
                    () -> connection.read(ClientboundPackets.CUSTOM_PAYLOAD, id, bytes));
        } finally {
            bytes.release();
        }
    }

    @Test
    void payloadAboveVanillaLimitIsMalformed() {
        CodecFixture connection = new CodecFixture(ProtocolRuntime.create(DATA));
        connection.phase(ConnectionPhase.PLAY);
        int id = DATA.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:custom_payload");
        ByteBuf bytes = Unpooled.buffer();
        try {
            Wire.writeString(bytes, "cultac:bridge", 32767);
            bytes.writeZero(1048576);
            assertEquals(
                    1048576,
                    connection
                            .read(ClientboundPackets.CUSTOM_PAYLOAD, id, bytes)
                            .data()
                            .length);
            bytes.writeByte(0);
            assertThrows(
                    MalformedPacketException.class,
                    () -> connection.read(ClientboundPackets.CUSTOM_PAYLOAD, id, bytes));
        } finally {
            bytes.release();
        }
    }
}
