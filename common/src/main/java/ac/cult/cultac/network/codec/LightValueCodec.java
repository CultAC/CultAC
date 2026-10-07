package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.LightValues;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** PacketEvents LightData framing through 26.2; 26.3 BIT_SET uses a byte array.
 * The 26.3 layout is verified against ClientboundLightUpdatePacketData.STREAM_CODEC. */
final class LightValueCodec {
    private LightValueCodec() {}

    static LightValues read(ByteBuf input, ProtocolVersion version) {
        BitSet[] masks = new BitSet[4];
        for (int index = 0; index < masks.length; index++) {
            if (version.atLeast(ProtocolVersion.V26_3)) {
                byte[] bytes = new byte[Wire.readLength(input, input.readableBytes())];
                input.readBytes(bytes);
                masks[index] = BitSet.valueOf(bytes);
            } else {
                long[] words = new long[Wire.readLength(input, input.readableBytes() / Long.BYTES)];
                for (int word = 0; word < words.length; word++) words[word] = input.readLong();
                masks[index] = BitSet.valueOf(words);
            }
        }
        return new LightValues(masks[0], masks[1], masks[2], masks[3], arrays(input), arrays(input));
    }

    private static List<byte[]> arrays(ByteBuf input) {
        int count = Wire.readLength(input, input.readableBytes());
        var arrays = new ArrayList<byte[]>(count);
        for (int index = 0; index < count; index++) {
            byte[] bytes = new byte[Wire.readLength(input, 2048)];
            input.readBytes(bytes);
            arrays.add(bytes);
        }
        return arrays;
    }
}
