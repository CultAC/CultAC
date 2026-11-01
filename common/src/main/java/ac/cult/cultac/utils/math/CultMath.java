package ac.cult.cultac.utils.math;

import java.util.List;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;

@UtilityClass
public class CultMath {
    public static final double MINIMUM_DIVISOR = ((Math.pow(0.2f, 3) * 8) * 0.15) - 1e-3; // 1e-3 for float imprecision

    @Contract(pure = true)
    public static double gcd(double a, double b) {
        if (a == 0) return 0;

        // Make sure a is larger than b
        if (a < b) {
            double temp = a;
            a = b;
            b = temp;
        }

        while (b > MINIMUM_DIVISOR) { // Minimum minecraft sensitivity
            double temp = a - (Math.floor(a / b) * b);
            a = b;
            b = temp;
        }

        return a;
    }

    @Contract(pure = true)
    public static int floor(double d) {
        return (int) Math.floor(d);
    }

    @Contract(pure = true)
    public static int ceil(double d) {
        return (int) Math.ceil(d);
    }

    // Should produce the same output as Math.floor() and Math.ceil() but mojang do it differently
    // Replicating what they do jussst in case
    @Contract(pure = true)
    public static int mojangFloor(double num) {
        final int floor = (int) num;
        return floor == num ? floor : floor - (int) (Double.doubleToRawLongBits(num) >>> 63);
    }

    @Contract(pure = true)
    public static double clamp(double num, double min, double max) {
        // The 3.0 simulation engine's collision-trace clamping reaches this with
        // inverted bounds when the probed collision result lies behind the
        // candidate's start (CollisionModifier.transformWithCollisions); swap so
        // the candidate is clamped to the nearer of the two endpoints instead of
        // being dragged behind its start.
        if (min > max) return clamp(num, max, min);
        if (num < min) {
            return min;
        }
        return Math.min(num, max);
    }

    @Contract(pure = true)
    public static int clamp(int num, int min, int max) {
        if (num < min) {
            return min;
        }
        return Math.min(num, max);
    }

    @Contract(pure = true)
    public static float clamp(float num, float min, float max) {
        if (num < min) {
            return min;
        }
        return Math.min(num, max);
    }

    @Contract(pure = true)
    public static float clampFloat(float num, float min, float max) {
        if (num < min) {
            return min;
        }
        return Math.min(num, max);
    }

    @Contract(pure = true)
    public static double lerp(double lerpAmount, double start, double end) {
        return start + lerpAmount * (end - start);
    }

    public static float lerp(float amount, float start, float end) {
        return start + amount * (end - start);
    }

    public static float sin(double radians) {
        return ac.cult.blocksim.engine.VanillaMath.sin(radians);
    }

    public static float cos(double radians) {
        return ac.cult.blocksim.engine.VanillaMath.cos(radians);
    }

    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    public static float rotLerp(float amount, float start, float end) {
        return start + amount * wrapDegrees(end - start);
    }

    public static double rotLerp(double amount, double start, double end) {
        return start + amount * wrapDegrees(end - start);
    }

    @Contract(pure = true)
    public static double frac(double p_14186_) {
        return p_14186_ - lfloor(p_14186_);
    }

    @Contract(pure = true)
    public static long lfloor(double p_14135_) {
        long i = (long) p_14135_;
        return p_14135_ < (double) i ? i - 1L : i;
    }

    @Contract(pure = true)
    public static float square(float value) {
        return value * value;
    }

    @Contract(pure = true)
    public static float sqrt(float value) {
        return (float) Math.sqrt(value);
    }

    @Contract(pure = true)
    public static boolean equal(double first, double second) {
        return Math.abs(second - first) < 1.0E-5F;
    }

    @Contract(pure = true)
    public static double square(double num) {
        return num * num;
    }

    @Contract(pure = true)
    public static double magnitude(double x, double z) {
        return Math.sqrt(x * x + z * z);
    }

    /** Calculates clicks per second without undercounting same-millisecond samples. */
    @Contract(pure = true)
    public static double getCps(List<Long> samples) {
        if (samples == null || samples.size() < 2) return 0;
        long spanMillis = Math.max(1, samples.get(samples.size() - 1) - samples.get(0));
        return samples.size() / (spanMillis / 1000.0);
    }
}
