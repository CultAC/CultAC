package ac.cult.blocksim.engine;


/** 26.3 Mth lookup and index arithmetic. Ordinary Math.sin is not equivalent. */
public final class VanillaMath {
    private static final double SIN_SCALE = 10430.378350470453;
    private static final float[] SIN = new float[65536];
    private static final double FRAC_BIAS = Double.longBitsToDouble(4805340802404319232L);
    private static final double[] ASIN = new double[257], COS = new double[257];
    static { for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i / SIN_SCALE); }
    static {
        for (int i = 0; i < ASIN.length; i++) {
            ASIN[i] = Math.asin(i / 256.0); COS[i] = Math.cos(ASIN[i]);
        }
    }
    private VanillaMath() { }

    public static float sin(double angle) { return SIN[(int) ((long) (angle * SIN_SCALE) & 65535L)]; }
    public static float cos(double angle) { return SIN[(int) ((long) (angle * SIN_SCALE + 16384.0) & 65535L)]; }

    /** Mth.atan2, used by the client's sign-front selection. */
    public static double atan2(double y, double x) {
        double squared = x * x + y * y;
        if (Double.isNaN(squared)) return Double.NaN;
        boolean negativeY = y < 0.0, negativeX = x < 0.0;
        if (negativeY) y = -y;
        if (negativeX) x = -x;
        boolean steep = y > x;
        if (steep) { double swap = x; x = y; y = swap; }
        double inverse = Double.longBitsToDouble(6910469410427058090L - (Double.doubleToRawLongBits(squared) >> 1));
        inverse *= 1.5 - 0.5 * squared * inverse * inverse;
        x *= inverse; y *= inverse;
        double rounded = FRAC_BIAS + y;
        int index = (int)Double.doubleToRawLongBits(rounded);
        double error = y * COS[index] - x * (rounded - FRAC_BIAS);
        double angle = ASIN[index] + (6.0 + error * error) * error * 0.16666666666666666;
        if (steep) angle = Math.PI / 2 - angle;
        if (negativeX) angle = Math.PI - angle;
        return negativeY ? -angle : angle;
    }
    public static float degreesDifferenceAbs(float from, float to) {
        float difference = (to - from) % 360.0F;
        if (difference >= 180.0F) difference -= 360.0F;
        if (difference < -180.0F) difference += 360.0F;
        return Math.abs(difference);
    }
}
