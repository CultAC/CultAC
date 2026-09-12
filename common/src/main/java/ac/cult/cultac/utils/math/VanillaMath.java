package ac.cult.cultac.utils.math;

import ac.cult.cultac.network.protocol.ClientVersion;

public class VanillaMath {
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; ++i) {
            SIN[i] = (float) StrictMath.sin(i * 3.141592653589793 * 2.0 / 65536.0);
        }
    }

    // Mth switched to double indexing and long masking in 1.21.11.
    private static final float[] MODERN_SIN = new float[65536];
    static {
        for (int i = 0; i < MODERN_SIN.length; i++) {
            MODERN_SIN[i] = (float) StrictMath.sin(i / 10430.378350470453D);
        }
    }

    public static float sin(ClientVersion version, float angle) {
        return version.isOlderThan(ClientVersion.V_1_21_11) ? sin(angle)
                : MODERN_SIN[(int) ((long) (angle * 10430.378350470453D) & 65535L)];
    }

    public static float cos(ClientVersion version, float angle) {
        return version.isOlderThan(ClientVersion.V_1_21_11) ? cos(angle)
                : MODERN_SIN[(int) ((long) (angle * 10430.378350470453D + 16384.0D) & 65535L)];
    }

    public static float sin(float f) {
        return SIN[(int) (f * 10430.378f) & 0xFFFF];
    }

    public static float cos(float f) {
        return SIN[(int) (f * 10430.378f + 16384.0f) & 0xFFFF];
    }
}
