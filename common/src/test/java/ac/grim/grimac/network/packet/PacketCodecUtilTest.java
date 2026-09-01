package ac.grim.grimac.network.packet;

import ac.grim.grimac.network.protocol.ClientVersion;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PacketCodecUtilTest {
    private static final Vec3 INPUT = new Vec3(0.123456789D, -0.0784000015D, 4.5D);

    @Test
    public void legacyCodecTruncatesTowardZeroAndClampsToSignedShort() {
        Vec3 encoded = PacketCodecUtil.quantizeLegacyVelocity(INPUT);
        assertEquals(987.0D / 8000.0D, encoded.x, 0.0D);
        assertEquals(-627.0D / 8000.0D, encoded.y, 0.0D);
        assertEquals(Short.MAX_VALUE / 8000.0D, encoded.z, 0.0D);

        Vec3 negativeClamp = PacketCodecUtil.quantizeLegacyVelocity(new Vec3(-4.5D, 0.0D, 0.0D));
        assertEquals(Short.MIN_VALUE / 8000.0D, negativeClamp.x, 0.0D);
    }

    @Test
    public void newServerToLegacyClientAppliesLpThenViaLegacyCodec() {
        Vec3 expected = PacketCodecUtil.quantizeLegacyVelocity(PacketCodecUtil.quantizeLpVec3(INPUT));
        Vec3 actual = PacketCodecUtil.quantizeClientboundVelocity(
                ClientVersion.V_1_21_9,
                ClientVersion.V_1_21_7,
                INPUT);
        assertVecEquals(expected, actual);
    }

    @Test
    public void legacyServerToNewClientAppliesLegacyThenViaLpCodec() {
        Vec3 expected = PacketCodecUtil.quantizeLpVec3(PacketCodecUtil.quantizeLegacyVelocity(INPUT));
        Vec3 actual = PacketCodecUtil.quantizeClientboundVelocity(
                ClientVersion.V_1_21_7,
                ClientVersion.V_1_21_9,
                INPUT);
        assertVecEquals(expected, actual);
    }

    @Test
    public void matchingEndpointCodecIsAppliedExactlyOnce() {
        assertVecEquals(
                PacketCodecUtil.quantizeLegacyVelocity(INPUT),
                PacketCodecUtil.quantizeClientboundVelocity(
                        ClientVersion.V_1_21_7, ClientVersion.V_1_21_7, INPUT));
        assertVecEquals(
                PacketCodecUtil.quantizeLpVec3(INPUT),
                PacketCodecUtil.quantizeClientboundVelocity(
                        ClientVersion.V_1_21_9, ClientVersion.V_1_21_9, INPUT));
    }

    private static void assertVecEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 0.0D);
        assertEquals(expected.y, actual.y, 0.0D);
        assertEquals(expected.z, actual.z, 0.0D);
    }
}
