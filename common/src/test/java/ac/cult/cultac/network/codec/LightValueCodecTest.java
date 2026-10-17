package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.Test;

class LightValueCodecTest {
    @Test
    void skipConsumesExactlyTheLightPayloadForBothMaskFormats() {
        for (var version : List.of(ProtocolVersion.V26_2, ProtocolVersion.V26_3)) {
            var input = Unpooled.buffer();
            try {
                for (int mask = 0; mask < 4; mask++) {
                    Wire.writeVarInt(input, mask == 0 ? 1 : 0);
                    if (mask == 0) {
                        if (version == ProtocolVersion.V26_3) input.writeByte(1);
                        else input.writeLong(1);
                    }
                }
                writeArrays(input, 1);
                writeArrays(input, 2);
                input.writeByte(42);
                LightValueCodec.skip(input, version);
                assertEquals(42, input.readByte());
                assertFalse(input.isReadable());
            } finally {
                input.release();
            }
        }
    }

    @Test
    void oversizedLayerIsRejected() {
        var input = Unpooled.buffer();
        try {
            for (int mask = 0; mask < 4; mask++) Wire.writeVarInt(input, 0);
            Wire.writeVarInt(input, 1);
            Wire.writeVarInt(input, 2049);
            input.writeZero(2049);
            assertThrows(MalformedPacketException.class, () -> LightValueCodec.skip(input, ProtocolVersion.V26_3));
        } finally {
            input.release();
        }
    }

    private static void writeArrays(ByteBuf output, int count) {
        Wire.writeVarInt(output, count);
        for (int i = 0; i < count; i++) {
            Wire.writeVarInt(output, 2048);
            output.writeZero(2048);
        }
    }
}
