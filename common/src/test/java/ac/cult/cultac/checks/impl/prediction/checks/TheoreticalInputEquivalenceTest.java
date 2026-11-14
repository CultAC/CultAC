package ac.cult.cultac.checks.impl.prediction.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.math.Vec3;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The theoretical input written into an array equals, bit for bit, the Vec3 overload, which is unchanged since
 * ca9de4b40, and the handler's length of it equals {@link Vec3#length}.
 */
class TheoreticalInputEquivalenceTest {
    private static final double[] MOVEMENTS = {
        0.0D,
        -0.0D,
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.MIN_VALUE,
        -Double.MIN_VALUE,
        Double.MAX_VALUE,
        1.0E-300D,
        0.2806D,
        -0.13D,
        1.0D
    };
    private static final float[] YAWS = {
        0.0F,
        -0.0F,
        90.0F,
        -90.0F,
        180.0F,
        45.0F,
        Float.NaN,
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
        1.0E10F,
        -3.4E38F,
        Float.MAX_VALUE,
        Float.MIN_VALUE,
        123456.7F
    };

    @Test
    void theArrayFormMatchesTheVec3FormBitForBit() {
        SplittableRandom random = new SplittableRandom(3);
        ClientVersion[] versions = ClientVersion.values();
        double[] out = new double[2];
        for (int i = 0; i < 2_000_000; i++) {
            double x = movement(random);
            double z = movement(random);
            float yaw = random.nextInt(3) == 0 ? YAWS[random.nextInt(YAWS.length)] : (float) random.nextDouble(-1e4, 1e4);
            ClientVersion version = versions[random.nextInt(versions.length)];
            Vec3 want = HorizontalAnalyzer.getBestTheoreticalPlayerInput(x, z, yaw, version);
            out[0] = random.nextDouble();
            out[1] = random.nextDouble();
            HorizontalAnalyzer.getBestTheoreticalPlayerInput(out, x, z, yaw, version);
            double length = Math.sqrt(out[0] * out[0] + 0.0D * 0.0D + out[1] * out[1]);
            String context = x + " " + z + " " + yaw + " " + version;
            assertEquals(bits(want.x), bits(out[0]), context);
            assertEquals(bits(want.z), bits(out[1]), context);
            assertEquals(bits(want.length()), bits(length), context);
        }
    }

    // The JIT and the interpreter give a NaN different sign bits, so NaNs compare by class only.
    private static long bits(double value) {
        return Double.isNaN(value) ? Double.doubleToLongBits(value) : Double.doubleToRawLongBits(value);
    }

    private static double movement(SplittableRandom random) {
        return random.nextInt(3) == 0 ? MOVEMENTS[random.nextInt(MOVEMENTS.length)] : random.nextDouble(-2, 2);
    }
}
