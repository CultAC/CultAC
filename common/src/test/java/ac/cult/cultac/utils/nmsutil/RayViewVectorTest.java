package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vector3dm;
import org.junit.jupiter.api.Test;

// The placement and attack rays must equal Entity#calculateViewVector for the client's version.
class RayViewVectorTest {
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) Math.sin(i / 10430.378350470453);
        }
    }

    // Yaws where -sin(yaw) != sin(-yaw) in the lookup table, and pitches where the
    // 1.21.10 float index and the 1.21.11 double index pick different entries.
    private static final float[][] LOOKS = {
        {-176.38F, 30.0F}, {-90.0F, 0.0F}, {-180.0F, 45.0F}, {120.5F, -71.12F}, {-33.0F, -67.5F}, {10.0F, -59.87F}
    };

    @Test
    void matchesLegacyClientViewVector() {
        assertMatches(ClientVersion.V_1_21_2, false);
        assertMatches(ClientVersion.V_1_21_9, false);
    }

    @Test
    void matchesModernClientViewVector() {
        assertMatches(ClientVersion.V_1_21_11, true);
        assertMatches(ClientVersion.V_26_2, true);
        assertMatches(ClientVersion.V_26_3, true);
    }

    @Test
    void indexChangeAffectsTheVector() {
        assertNotEquals(vanilla(120.5F, -71.12F, false).getZ(), vanilla(120.5F, -71.12F, true).getZ());
    }

    private static void assertMatches(ClientVersion version, boolean modern) {
        CultPlayer player = mock(CultPlayer.class);
        when(player.getClientVersion()).thenReturn(version);
        player.trigHandler = new TrigHandler(player);
        for (float[] look : LOOKS) {
            Vector3dm expected = vanilla(look[0], look[1], modern);
            Vector3dm actual = Ray.calculateDirection(player, look[0], look[1]);
            String at = version + " yaw=" + look[0] + " pitch=" + look[1];
            assertEquals(expected.getX(), actual.getX(), 0.0, at);
            assertEquals(expected.getY(), actual.getY(), 0.0, at);
            assertEquals(expected.getZ(), actual.getZ(), 0.0, at);
        }
    }

    // Entity#calculateViewVector(xRot = pitch, yRot = yaw) with that version's Mth.
    private static Vector3dm vanilla(float yaw, float pitch, boolean modern) {
        float realXRot = pitch * (float) (Math.PI / 180.0);
        float realYRot = -yaw * (float) (Math.PI / 180.0);
        float yCos = cos(realYRot, modern);
        float ySin = sin(realYRot, modern);
        float xCos = cos(realXRot, modern);
        float xSin = sin(realXRot, modern);
        return new Vector3dm(ySin * xCos, -xSin, yCos * xCos);
    }

    private static float sin(float f, boolean modern) {
        return modern ? SIN[(int) ((long) (f * 10430.378350470453) & 65535L)] : SIN[(int) (f * 10430.378F) & 65535];
    }

    private static float cos(float f, boolean modern) {
        return modern
                ? SIN[(int) ((long) (f * 10430.378350470453 + 16384.0) & 65535L)]
                : SIN[(int) (f * 10430.378F + 16384.0F) & 65535];
    }
}
