package ac.cult.cultac.network.packet;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.math.Vec3;

public final class PacketCodecUtil {
    public static final double ENTITY_POSITION_PACKET_ROUNDING = (1.0D / 4096.0D) / 2.0D + 1.0E-10D;

    private static final long LP_DATA_BITS_MASK = 32767L;
    private static final double LP_MAX_QUANTIZED_VALUE = 32766.0D;
    private static final double LP_ABS_MAX_VALUE = 1.7179869183E10D;
    private static final double LP_ABS_MIN_VALUE = 3.051944088384301E-5D;

    private PacketCodecUtil() {}

    public static Vec3 quantizeLpVec3(Vec3 movement) {
        // Mirrors net.minecraft.network.LpVec3, used by ClientboundSetEntityMotionPacket
        // and ClientboundAddEntityPacket. Cult must predict the vector after packet encoding.
        double x = sanitizeLp(movement.x);
        double y = sanitizeLp(movement.y);
        double z = sanitizeLp(movement.z);
        double chessboardLength = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (chessboardLength < LP_ABS_MIN_VALUE) {
            return Vec3.ZERO;
        }

        long scale = (long) Math.ceil(chessboardLength);
        return new Vec3(
                unpackLp(packLp(x / scale)) * scale,
                unpackLp(packLp(y / scale)) * scale,
                unpackLp(packLp(z / scale)) * scale);
    }

    /**
     * Returns the client-visible velocity from an already decoded observed packet.
     * Via reads that representation and encodes only the destination representation.
     */
    public static Vec3 clientVelocityFromObserved(
            ProtocolVersion observedProtocol, ClientVersion clientVersion, Vec3 movement) {
        boolean observedUsesLp = observedProtocol.atLeast(ProtocolVersion.V1_21_9);
        boolean clientUsesLp = clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_9);
        if (observedUsesLp == clientUsesLp) return movement;
        return clientUsesLp ? quantizeLpVec3(movement) : quantizeViaLegacyVelocity(movement);
    }

    // Authored vectors require source encoding first; decoded packet consumers use
    // clientVelocityFromObserved instead, to avoid truncating legacy shorts twice.
    static Vec3 quantizeClientboundVelocity(ClientVersion sourceVersion, ClientVersion clientVersion, Vec3 movement) {
        boolean sourceUsesLp = sourceVersion.isNewerThanOrEquals(ClientVersion.V_1_21_9);
        boolean clientUsesLp = clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_9);

        Vec3 sourceEncoded = sourceUsesLp ? quantizeLpVec3(movement) : quantizeLegacyVelocity(movement);
        if (sourceUsesLp == clientUsesLp) {
            return sourceEncoded;
        }

        // Via reads the already encoded source representation and writes the target one.
        return clientUsesLp ? quantizeLpVec3(sourceEncoded) : quantizeViaLegacyVelocity(sourceEncoded);
    }

    static Vec3 quantizeLegacyVelocity(Vec3 movement) {
        return new Vec3(
                quantizeLegacyVelocityAxis(movement.x),
                quantizeLegacyVelocityAxis(movement.y),
                quantizeLegacyVelocityAxis(movement.z));
    }

    private static double quantizeLegacyVelocityAxis(double value) {
        // Authored legacy packet encoding truncates before the client divides by 8000.
        long encoded = (long) (value * 8000.0D);
        encoded = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, encoded));
        return encoded / 8000.0D;
    }

    private static Vec3 quantizeViaLegacyVelocity(Vec3 movement) {
        return new Vec3(viaLegacyAxis(movement.x), viaLegacyAxis(movement.y), viaLegacyAxis(movement.z));
    }

    private static double viaLegacyAxis(double value) {
        // Pinned ViaBackwards VelocityUtil#toLegacyVelocity rounds decoded LP
        // vectors, then clamps to signed shorts. This differs from native encoding.
        long encoded = Math.round(value * 8000.0D);
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, encoded)) / 8000.0D;
    }

    public static float quantizeRotationByte(float rotation) {
        // Mirrors ByteBufCodecs.ROTATION_BYTE for packets that store float rotations before encoding.
        return (byte) Math.floor(rotation * 256.0F / 360.0F) * 360 / 256.0F;
    }

    public static int decodeSignedShort(int value) {
        // Mirrors FriendlyByteBuf.readShort after a packet wrote an int with writeShort.
        return (short) value;
    }

    public static int decodeUnsignedByte(int value) {
        // Mirrors FriendlyByteBuf.readUnsignedByte after a packet wrote an int with writeByte.
        return Byte.toUnsignedInt((byte) value);
    }

    public static Vec3 decodeRelativeEntityPosition(Vec3 base, double deltaX, double deltaY, double deltaZ) {
        return decodeRelativeEntityPosition(
                base, Math.round(deltaX * 4096.0D), Math.round(deltaY * 4096.0D), Math.round(deltaZ * 4096.0D));
    }

    public static Vec3 decodeRelativeEntityPosition(Vec3 base, long xa, long ya, long za) {
        // Mirrors VecDeltaCodec.decode, used by ClientboundMoveEntityPacket on the client.
        if (xa == 0L && ya == 0L && za == 0L) {
            return base;
        }

        return new Vec3(
                xa == 0L ? base.x : decodeEntityPosition(encodeEntityPosition(base.x) + xa),
                ya == 0L ? base.y : decodeEntityPosition(encodeEntityPosition(base.y) + ya),
                za == 0L ? base.z : decodeEntityPosition(encodeEntityPosition(base.z) + za));
    }

    public static boolean matchesEntityPositionPacketRounding(Vec3 expected, Vec3 actual) {
        return Math.abs(expected.x - actual.x) <= ENTITY_POSITION_PACKET_ROUNDING
                && Math.abs(expected.y - actual.y) <= ENTITY_POSITION_PACKET_ROUNDING
                && Math.abs(expected.z - actual.z) <= ENTITY_POSITION_PACKET_ROUNDING;
    }

    private static double sanitizeLp(double value) {
        if (Double.isNaN(value)) {
            return 0.0D;
        }
        return Math.max(-LP_ABS_MAX_VALUE, Math.min(LP_ABS_MAX_VALUE, value));
    }

    private static long packLp(double value) {
        return Math.round((value * 0.5D + 0.5D) * LP_MAX_QUANTIZED_VALUE);
    }

    private static double unpackLp(long value) {
        return Math.min((double) (value & LP_DATA_BITS_MASK), LP_MAX_QUANTIZED_VALUE) * 2.0D / LP_MAX_QUANTIZED_VALUE
                - 1.0D;
    }

    private static long encodeEntityPosition(double value) {
        return Math.round(value * 4096.0D);
    }

    private static double decodeEntityPosition(long value) {
        return value / 4096.0D;
    }
}
