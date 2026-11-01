package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.utils.math.CultMath;

/** AbstractBoat#clampRotation for a player passenger, and the yaws it leaves a rider with. */
public final class BoatRiderYaw {
    private static final float LIMIT = 105.0F;
    // A range with at most this many floats or cells is searched as a whole.
    private static final int WHOLE_SEARCH = 64;

    private BoatRiderYaw() {}

    /** AbstractBoat#clampRotation: {@code (yaw + clamp(delta, -105, 105)) - delta}, delta = wrapDegrees(yaw - boatYaw). */
    public static float clampRotation(float yaw, float boatYaw) {
        float delta = CultMath.wrapDegrees(yaw - boatYaw);
        return yaw + CultMath.clamp(delta, -LIMIT, LIMIT) - delta;
    }

    /**
     * Whether a rider's mouse turn can end on {@code yaw} in a boat at {@code boatYaw}: Entity#turn ends with
     * AbstractBoat#clampRotation, which keeps a yaw its comparison puts within 105 degrees of the boat and otherwise
     * returns {@link #clampRotation(float, float)} of the turned yaw. A yaw outside that comparison is held only when
     * some finite float yaw clamps to exactly it.
     */
    public static boolean canHold(float yaw, float boatYaw) {
        float delta = CultMath.wrapDegrees(yaw - boatYaw);
        if (delta >= -LIMIT && delta <= LIMIT) return true;
        if (!Float.isFinite(yaw) || !Float.isFinite(boatYaw)) return false;
        return isClampOf(yaw, boatYaw);
    }

    /*
     * The search for a float z with clampRotation(z, b) == u. With s = z + t, x = z - b, d = wrapDegrees(x) and
     * t = clamp(d), u = s - d up to rounding, so every such z lies within 75 degrees plus rounding of u. Each x fixes
     * d and t, and z ordered within one x orders the output; within one s, with t fixed, the output is reversed.
     * Where t, the winding of x and the binades of z + t and z - b are fixed, shifting z by an even multiple p of
     * every grid involved shifts s and x by p alike, so one window of p next to the end nearest zero holds every
     * output of that stretch.
     */
    private static boolean isClampOf(float u, float b) {
        double r = 2.0 * Math.ulp(Math.abs(u) + 512.0F);
        int lo = keyAtOrBelow(u - 75.0 - r);
        int hi = keyAtOrAbove(u + 75.0 + r);
        if ((long) hi - lo < WHOLE_SEARCH || cells(value(lo) - b, value(hi) - b) < WHOLE_SEARCH) {
            return search(lo, hi, u, b, Float.NaN);
        }
        int inLo = keyAtOrBelow(u - r);
        int inHi = keyAtOrAbove(u + r);
        for (int start = lo; start <= hi; ) {
            int label = label(value(start), b);
            int end = lastWithLabel(start, hi, label, b);
            int side = Math.floorMod(label, 3);
            if (side == 1) {
                int from = Math.max(start, inLo);
                int to = Math.min(end, inHi);
                if (from <= to && search(from, to, u, b, Float.NaN)) return true;
            } else if (clamped(start, end, u, b, side == 2 ? LIMIT : -LIMIT)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    // Splits a clamped run of fixed winding at z = 0 and the binade edges of z - b and of z + t outside the run
    // where z + t is exact.
    private static boolean clamped(int start, int end, float u, float b, float t) {
        while (start <= end) {
            float z = value(start);
            double next = nextEdge(z, b, t);
            int stop = end;
            if (next <= value(end)) stop = Math.max(start, Math.min(end, keyAtOrAbove(next) - 1));
            if (stretch(start, stop, u, b, t)) return true;
            start = stop + 1;
        }
        return false;
    }

    private static double nextEdge(float z, float b, float t) {
        double next = z < 0 ? 0.0 : Double.POSITIVE_INFINITY;
        double x = (double) z - b;
        next = Math.min(next, b + (x >= 0 ? powerAbove(x) : -powerBelow(-x)));
        double s = (double) z + t;
        double edge;
        if (Math.abs(s) < 32.0) edge = 32.0;
        else if (s > 0) edge = powerAbove(s);
        else edge = -Math.max(32.0, powerBelow(-s));
        return Math.min(next, edge - t);
    }

    // The least power of two above v > 0.
    private static double powerAbove(double v) {
        return Math.scalb(1.0, Math.getExponent(v) + 1);
    }

    // The greatest power of two below v > 0.
    private static double powerBelow(double v) {
        double p = Math.scalb(1.0, Math.getExponent(v));
        return p < v ? p : p / 2;
    }

    private static boolean stretch(int start, int end, float u, float b, float t) {
        float za = value(start);
        float zb = value(end);
        double grid = Math.max(
                Math.max(Math.ulp(za), Math.ulp(zb)),
                Math.max(Math.max(Math.ulp(za - b), Math.ulp(zb - b)), Math.max(Math.ulp(za + t), Math.ulp(zb + t))));
        double p = 2.0 * grid;
        if ((double) zb - za <= 8.0 * p) return search(start, end, u, b, t);
        if (start >= 0) {
            return search(start, keyAtOrAbove(za + 4.0 * p), u, b, t)
                    || search(keyAtOrBelow(zb - 2.0 * p), end, u, b, t);
        }
        return search(keyAtOrBelow(zb - 4.0 * p), end, u, b, t) || search(start, keyAtOrAbove(za + 2.0 * p), u, b, t);
    }

    // Searches every float in [lo, hi] by the coarsest of the floats themselves, the cells of one x, and, with t fixed,
    // the cells of one s.
    private static boolean search(int lo, int hi, float u, float b, float t) {
        float za = value(lo);
        float zb = value(hi);
        double floats = (double) hi - lo + 1;
        double xCells = cells(za - b, zb - b);
        double sCells = Float.isNaN(t) ? Double.POSITIVE_INFINITY : cells(za + t, zb + t);
        if (floats <= xCells && floats <= sCells) {
            for (int k = lo; k <= hi; k++) {
                if (clampRotation(value(k), b) == u) return true;
            }
            return false;
        }
        if (xCells <= sCells) {
            for (int k = lo; k <= hi; ) {
                int end = lastWithX(k, hi, value(k) - b, b);
                int first = firstAtLeast(k, end, u, b);
                if (first <= end && clampRotation(value(first), b) == u) return true;
                k = end + 1;
            }
            return false;
        }
        for (int k = lo; k <= hi; ) {
            int end = lastWithS(k, hi, value(k) + t, t);
            int first = firstAtMost(k, end, u, b);
            if (first <= end && clampRotation(value(first), b) == u) return true;
            k = end + 1;
        }
        return false;
    }

    // An upper bound on the distinct floats rounded into [a, b].
    private static double cells(float a, float b) {
        float near = a <= 0 && b >= 0 ? 0.0F : Math.min(Math.abs(a), Math.abs(b));
        return ((double) b - a) / Math.ulp(near) + 2.0;
    }

    // The winding of x = z - b times three, plus 0, 1 or 2 for clampRotation's lower, open and upper side.
    private static int label(float z, float b) {
        float x = z - b;
        float d = CultMath.wrapDegrees(x);
        int winding = (int) (((double) x - d) / 360.0);
        return winding * 3 + (d < -LIMIT ? 0 : d <= LIMIT ? 1 : 2);
    }

    private static int lastWithLabel(int lo, int hi, int label, float b) {
        while (lo < hi) {
            int mid = (int) (((long) lo + hi + 1) >> 1);
            if (label(value(mid), b) <= label) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private static int lastWithX(int lo, int hi, float x, float b) {
        while (lo < hi) {
            int mid = (int) (((long) lo + hi + 1) >> 1);
            if (value(mid) - b <= x) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private static int lastWithS(int lo, int hi, float s, float t) {
        while (lo < hi) {
            int mid = (int) (((long) lo + hi + 1) >> 1);
            if (value(mid) + t <= s) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private static int firstAtLeast(int lo, int hi, float u, float b) {
        int end = hi + 1;
        while (lo < end) {
            int mid = (int) (((long) lo + end) >> 1);
            if (clampRotation(value(mid), b) >= u) end = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    private static int firstAtMost(int lo, int hi, float u, float b) {
        int end = hi + 1;
        while (lo < end) {
            int mid = (int) (((long) lo + end) >> 1);
            if (clampRotation(value(mid), b) <= u) end = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    private static final int MIN_KEY = key(-Float.MAX_VALUE);
    private static final int MAX_KEY = key(Float.MAX_VALUE);

    // Orders floats as ints: adjacent floats have adjacent keys, -0 just below +0.
    private static int key(float f) {
        int bits = Float.floatToRawIntBits(f);
        return bits ^ ((bits >> 31) & 0x7fffffff);
    }

    private static float value(int key) {
        return Float.intBitsToFloat(key ^ ((key >> 31) & 0x7fffffff));
    }

    private static int keyAtOrBelow(double v) {
        float f = (float) v;
        if (f > v) f = Math.nextDown(f);
        return Math.max(MIN_KEY, Math.min(MAX_KEY, key(f)));
    }

    private static int keyAtOrAbove(double v) {
        float f = (float) v;
        if (f < v) f = Math.nextUp(f);
        return Math.max(MIN_KEY, Math.min(MAX_KEY, key(f)));
    }
}
