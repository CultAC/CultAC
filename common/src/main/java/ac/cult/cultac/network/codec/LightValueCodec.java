package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

/** PacketEvents LightData framing through 26.2; 26.3 BIT_SET uses a byte array.
 * The 26.3 layout is verified against ClientboundLightUpdatePacketData.STREAM_CODEC. */
final class LightValueCodec {
    private LightValueCodec() {}

    static void skip(ByteBuf input, ProtocolVersion version) {
        for (int index = 0; index < 4; index++) {
            if (version.atLeast(ProtocolVersion.V26_3)) {
                input.skipBytes(Wire.readLength(input, input.readableBytes()));
            } else {
                input.skipBytes(Wire.readLength(input, input.readableBytes() / Long.BYTES) * Long.BYTES);
            }
        }
        skipArrays(input);
        skipArrays(input);
    }

    private static void skipArrays(ByteBuf input) {
        int count = Wire.readLength(input, input.readableBytes());
        for (int index = 0; index < count; index++) input.skipBytes(Wire.readLength(input, 2048));
    }
}
