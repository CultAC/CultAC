package ac.cult.cultac.protocol.wire;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Wire primitives. Helpers consume bytes; packet decoding uses a duplicate buffer. */
public final class Wire {
    public static final int MAX_STRING_LENGTH = 32767;
    private static final double LP_MIN = 3.051944088384301E-5;
    private static final double LP_MAX = 1.7179869183E10;

    private Wire() {}

    /** Length-prefixed IDs, following PacketEvents' readVarIntArray loop. */
    public static List<Integer> readVarIntList(ByteBuf buffer) {
        return readVarIntList(buffer, readVarInt(buffer));
    }

    public static List<Integer> readVarIntList(ByteBuf buffer, int count) {
        // Every VarInt needs at least one byte; reject impossible counts before allocation.
        if (count < 0 || count > buffer.readableBytes())
            throw new MalformedPacketException("Invalid VarInt list size " + count);
        var values = new ArrayList<Integer>(count);
        for (int i = 0; i < count; i++) values.add(readVarInt(buffer));
        return values;
    }

    public static void requireBytes(ByteBuf buffer, int length) {
        if (length < 0 || length > buffer.readableBytes()) {
            throw new MalformedPacketException("Expected " + length + " bytes, available " + buffer.readableBytes());
        }
    }

    /** A vanilla length prefix with an explicit allocation/collection limit. */
    public static int readLength(ByteBuf buffer, int maximum) {
        int length = readVarInt(buffer);
        if (length < 0 || length > maximum) throw new MalformedPacketException("Invalid length " + length);
        return length;
    }

    /** FriendlyByteBuf.readEnum is strict; ID mappers with defaults must not use this helper. */
    public static int readEnumOrdinal(ByteBuf buffer, int count) {
        int ordinal = readVarInt(buffer);
        if (ordinal < 0 || ordinal >= count) throw new MalformedPacketException("Invalid enum ordinal " + ordinal);
        return ordinal;
    }

    public static int readVarInt(ByteBuf buffer) {
        int value = 0;
        // Vanilla accepts non-minimal encodings and unused high bits in byte five.
        for (int index = 0; index < 5; index++) {
            requireBytes(buffer, 1);
            int next = buffer.readUnsignedByte();
            value |= (next & 0x7f) << (index * 7);
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        throw new MalformedPacketException("VarInt exceeds five bytes");
    }

    /** Inspect a packet ID without changing indices or creating a buffer view. */
    public static int peekVarInt(ByteBuf buffer) {
        int value = 0;
        int start = buffer.readerIndex();
        for (int index = 0; index < 5; index++) {
            if (start + index >= buffer.writerIndex()) throw new MalformedPacketException("Truncated VarInt");
            int next = buffer.getUnsignedByte(start + index);
            value |= (next & 0x7f) << (index * 7);
            if ((next & 0x80) == 0) return value;
        }
        throw new MalformedPacketException("VarInt exceeds five bytes");
    }

    public static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & ~0x7f) != 0) {
            buffer.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }

    public static long readVarLong(ByteBuf buffer) {
        long value = 0;
        for (int index = 0; index < 10; index++) {
            requireBytes(buffer, 1);
            int next = buffer.readUnsignedByte();
            value |= (long) (next & 0x7f) << (index * 7);
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        throw new MalformedPacketException("VarLong exceeds ten bytes");
    }

    public static void writeVarLong(ByteBuf buffer, long value) {
        while ((value & ~0x7fL) != 0) {
            buffer.writeByte((int) (value & 0x7f) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte((int) value);
    }

    public static String readString(ByteBuf buffer, int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("Negative string limit");
        }
        int bytes = readVarInt(buffer);
        if (bytes < 0 || bytes > ByteBufUtil.utf8MaxBytes(maxLength)) {
            throw new MalformedPacketException("String byte length exceeds " + maxLength + " UTF-16 code units");
        }
        requireBytes(buffer, bytes);
        // Utf8String.read uses replacement decoding for malformed UTF-8 too.
        String value = buffer.toString(buffer.readerIndex(), bytes, StandardCharsets.UTF_8);
        buffer.skipBytes(bytes);
        if (value.length() > maxLength) {
            throw new MalformedPacketException("String exceeds " + maxLength + " UTF-16 code units");
        }
        return value;
    }

    public static void writeString(ByteBuf buffer, CharSequence value, int maxLength) {
        if (maxLength < 0 || value.length() > maxLength) {
            throw new IllegalArgumentException("String exceeds " + maxLength + " UTF-16 code units");
        }
        // Netty's handling of unpaired surrogates is part of vanilla's encoding.
        ByteBuf encoded = buffer.alloc().buffer(ByteBufUtil.utf8MaxBytes(value));
        try {
            int length = ByteBufUtil.writeUtf8(encoded, value);
            if (length > 3L * maxLength) {
                throw new IllegalArgumentException("Encoded string exceeds limit");
            }
            writeVarInt(buffer, length);
            buffer.writeBytes(encoded);
        } finally {
            encoded.release();
        }
    }

    /** ResourceLocation/Identifier parsing: an omitted namespace defaults to minecraft. */
    public static String readIdentifier(ByteBuf buffer) {
        String value = readString(buffer, 32767);
        int separator = value.indexOf(':');
        String namespace = separator > 0 ? value.substring(0, separator) : "minecraft";
        String path = separator >= 0 ? value.substring(separator + 1) : value;
        // Paper's identifier constructor checks the normalized spelling, including
        // the default namespace, against both UTF-16 and utf8MaxBytes bounds.
        int length = namespace.length() + 1 + path.length();
        if (length > 32767 || ByteBufUtil.utf8MaxBytes(length) > 65535) {
            throw new MalformedPacketException("Identifier exceeds Paper's length bound");
        }
        for (int i = 0; i < namespace.length(); i++) {
            if (!identifierCharacter(namespace.charAt(i))) {
                throw new MalformedPacketException("Invalid identifier namespace");
            }
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c != '/' && !identifierCharacter(c)) {
                throw new MalformedPacketException("Invalid identifier path");
            }
        }
        return namespace + ':' + path;
    }

    private static boolean identifierCharacter(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.';
    }

    public static UUID readUuid(ByteBuf buffer) {
        requireBytes(buffer, 16);
        return new UUID(buffer.readLong(), buffer.readLong());
    }

    public static void writeUuid(ByteBuf buffer, UUID value) {
        buffer.writeLong(value.getMostSignificantBits());
        buffer.writeLong(value.getLeastSignificantBits());
    }

    public static BlockPos readBlockPos(ByteBuf buffer) {
        requireBytes(buffer, 8);
        return BlockPos.of(buffer.readLong());
    }

    public static void writeBlockPos(ByteBuf buffer, BlockPos value) {
        buffer.writeLong(value.asLong());
    }

    public static float readAngle(ByteBuf buffer) {
        requireBytes(buffer, 1);
        return buffer.readByte() * (360.0f / 256.0f);
    }

    public static void writeAngle(ByteBuf buffer, ProtocolVersion version, float angle) {
        float scaled = angle * 256.0f / 360.0f;
        // From V26_1 vanilla Mth.floor delegates to Math.floor. Earlier supported
        // versions have observable integer overflow for extreme negative floats.
        if (version.atLeast(ProtocolVersion.V26_1)) {
            buffer.writeByte((int) Math.floor(scaled));
            return;
        }
        int truncated = (int) scaled;
        buffer.writeByte(scaled < truncated ? truncated - 1 : truncated);
    }

    public static Vec3d readShortVelocity(ByteBuf buffer) {
        requireBytes(buffer, 6);
        return new Vec3d(buffer.readShort() / 8000.0, buffer.readShort() / 8000.0, buffer.readShort() / 8000.0);
    }

    public static void writeShortVelocity(ByteBuf buffer, Vec3d velocity) {
        buffer.writeShort(quantizeShortVelocity(velocity.x()));
        buffer.writeShort(quantizeShortVelocity(velocity.y()));
        buffer.writeShort(quantizeShortVelocity(velocity.z()));
    }

    private static int quantizeShortVelocity(double value) {
        return (int) (Math.max(-3.9, Math.min(3.9, value)) * 8000.0);
    }

    /** Matches vanilla LpVec3, including its mixed byte order and unsigned scale. */
    public static Vec3d readLpVec3(ByteBuf buffer) {
        requireBytes(buffer, 1);
        int low = buffer.readUnsignedByte();
        if (low == 0) {
            return Vec3d.ZERO;
        }
        requireBytes(buffer, 5);
        int middle = buffer.readUnsignedByte();
        long packed = (buffer.readUnsignedInt() << 16) | (middle << 8) | low;
        long scale = low & 3;
        if ((low & 4) != 0) {
            scale |= (readVarInt(buffer) & 0xffffffffL) << 2;
        }
        return new Vec3d(unpackLp(packed >> 3) * scale, unpackLp(packed >> 18) * scale, unpackLp(packed >> 33) * scale);
    }

    public static void writeLpVec3(ByteBuf buffer, Vec3d value) {
        double x = sanitizeLp(value.x());
        double y = sanitizeLp(value.y());
        double z = sanitizeLp(value.z());
        double maximum = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (maximum < LP_MIN) {
            buffer.writeByte(0);
            return;
        }
        long scale = (long) Math.ceil(maximum);
        boolean continued = scale > 3;
        long packed = (scale & 3)
                | (continued ? 4 : 0)
                | packLp(x / scale) << 3
                | packLp(y / scale) << 18
                | packLp(z / scale) << 33;
        buffer.writeByte((int) packed);
        buffer.writeByte((int) (packed >> 8));
        buffer.writeInt((int) (packed >> 16));
        if (continued) {
            writeVarInt(buffer, (int) (scale >> 2));
        }
    }

    private static double sanitizeLp(double value) {
        return Double.isNaN(value) ? 0 : Math.max(-LP_MAX, Math.min(LP_MAX, value));
    }

    private static long packLp(double value) {
        return Math.round((value * 0.5 + 0.5) * 32766.0);
    }

    private static double unpackLp(long value) {
        return Math.min(value & 0x7fff, 32766) * 2.0 / 32766.0 - 1;
    }

    public static Vec3d readVec3(ByteBuf buffer) {
        requireBytes(buffer, 24);
        return new Vec3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    public static void writeVec3(ByteBuf buffer, Vec3d value) {
        buffer.writeDouble(value.x()).writeDouble(value.y()).writeDouble(value.z());
    }

    public static Set<Relative> readRelatives(ByteBuf buffer) {
        requireBytes(buffer, 4);
        return Relative.unpack(buffer.readInt());
    }

    public static void writeRelatives(ByteBuf buffer, Set<Relative> relatives) {
        buffer.writeInt(Relative.pack(relatives));
    }
}
