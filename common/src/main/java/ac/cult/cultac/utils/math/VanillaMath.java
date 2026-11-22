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

    private static final double FRAC_BIAS = Double.longBitsToDouble(4805340802404319232L);
    private static final double[] ASIN_TAB = new double[257];
    private static final double[] COS_TAB = new double[257];

    static {
        for (int ind = 0; ind < 257; ind++) {
            double asin = Math.asin(ind / 256.0);
            COS_TAB[ind] = Math.cos(asin);
            ASIN_TAB[ind] = asin;
        }
    }

    /** Mth#atan2: the client's table-based arctangent. */
    public static double atan2(double y, double x) {
        double d2 = x * x + y * y;
        if (Double.isNaN(d2)) return Double.NaN;
        boolean negY = y < 0.0;
        if (negY) y = -y;
        boolean negX = x < 0.0;
        if (negX) x = -x;
        boolean steep = y > x;
        if (steep) {
            double t = x;
            x = y;
            y = t;
        }
        double rinv = fastInvSqrt(d2);
        x *= rinv;
        y *= rinv;
        double yp = FRAC_BIAS + y;
        int index = (int) Double.doubleToRawLongBits(yp);
        double phi = ASIN_TAB[index];
        double cPhi = COS_TAB[index];
        double sPhi = yp - FRAC_BIAS;
        double sd = y * cPhi - x * sPhi;
        double d = (6.0 + sd * sd) * sd * 0.16666666666666666;
        double theta = phi + d;
        if (steep) theta = (Math.PI / 2) - theta;
        if (negX) theta = Math.PI - theta;
        if (negY) theta = -theta;
        return theta;
    }

    private static double fastInvSqrt(double x) {
        double xhalf = 0.5 * x;
        long i = Double.doubleToRawLongBits(x);
        i = 6910469410427058090L - (i >> 1);
        x = Double.longBitsToDouble(i);
        return x * (1.5 - xhalf * x * x);
    }

    public static float sin(ClientVersion version, float angle) {
        return version.isOlderThan(ClientVersion.V_1_21_11)
                ? sin(angle)
                : MODERN_SIN[(int) ((long) (angle * 10430.378350470453D) & 65535L)];
    }

    public static float cos(ClientVersion version, float angle) {
        return version.isOlderThan(ClientVersion.V_1_21_11)
                ? cos(angle)
                : MODERN_SIN[(int) ((long) (angle * 10430.378350470453D + 16384.0D) & 65535L)];
    }

    public static float sin(float f) {
        return SIN[(int) (f * 10430.378f) & 0xFFFF];
    }

    public static float cos(float f) {
        return SIN[(int) (f * 10430.378f + 16384.0f) & 0xFFFF];
    }
}
