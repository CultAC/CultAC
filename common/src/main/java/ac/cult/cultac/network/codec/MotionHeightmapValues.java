package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.NbtSkipper;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import java.io.IOException;

/** Rain cover needs only MOTION_BLOCKING; skip the other heightmaps without retaining them. */
final class MotionHeightmapValues {
    private MotionHeightmapValues() {}

    static long[] read(ByteBuf input, ProtocolVersion version) {
        long[] motionBlocking = null;
        if (version.atLeast(ProtocolVersion.V1_21_5)) {
            int count = Wire.readLength(input, input.readableBytes());
            for (int i = 0; i < count; i++) {
                int type = Wire.readVarInt(input);
                int length = Wire.readLength(input, input.readableBytes() / Long.BYTES);
                if (type == 4) motionBlocking = longs(input, length);
                else input.skipBytes(length * Long.BYTES);
            }
            return motionBlocking;
        }

        int root = input.readUnsignedByte();
        if (root == 0) return null;
        if (root != 10) throw new MalformedPacketException("Expected heightmap compound");
        var stream = new ByteBufInputStream(input);
        try {
            int type;
            while ((type = input.readUnsignedByte()) != 0) {
                String name = stream.readUTF();
                if (name.equals("MOTION_BLOCKING") && type == 12) {
                    int length = input.readInt();
                    if (length < 0 || length > input.readableBytes() / Long.BYTES)
                        throw new MalformedPacketException("Invalid heightmap array length");
                    motionBlocking = longs(input, length);
                } else {
                    NbtSkipper.skipPayload(input, type, 1, 512);
                    if (name.equals("MOTION_BLOCKING")) motionBlocking = null;
                }
            }
        } catch (IOException failure) {
            throw new MalformedPacketException("Invalid heightmap field name", failure);
        }
        return motionBlocking;
    }

    private static long[] longs(ByteBuf input, int length) {
        long[] values = new long[length];
        for (int i = 0; i < length; i++) values[i] = input.readLong();
        return values;
    }
}
