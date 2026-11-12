package ac.cult.cultac.checks.impl.prediction.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class VehicleInputsEquivalenceTest {
    private static final float[] IMPULSES = {
        0.0F,
        -0.0F,
        1.0F,
        -1.0F,
        0.98F,
        -0.98F,
        0.5F,
        0.3F,
        Math.nextUp(1.0F),
        Math.nextDown(1.0F),
        Math.nextUp(0.0F),
        -Math.nextUp(0.0F),
        Float.MIN_NORMAL,
        Float.MAX_VALUE,
        Float.NaN,
        Float.intBitsToFloat(0x7fc00001),
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY
    };

    @Test
    void everyPairMatchesAFreshVectorBitForBit() {
        for (float horiz : IMPULSES) {
            for (float forward : IMPULSES) {
                Vec3 expected = new Vec3(horiz, 0.0, forward);
                Vec3 actual = SimulationProcessor.vehicleInputs(horiz, forward);
                String pair = horiz + ", " + forward;
                assertEquals(Double.doubleToRawLongBits(expected.x), Double.doubleToRawLongBits(actual.x), pair);
                assertEquals(Double.doubleToRawLongBits(expected.y), Double.doubleToRawLongBits(actual.y), pair);
                assertEquals(Double.doubleToRawLongBits(expected.z), Double.doubleToRawLongBits(actual.z), pair);
                assertEquals(Vec3.class, actual.getClass(), pair);
            }
        }
    }

    @Test
    void noInputIsTheSharedZero() {
        assertSame(Vec3.ZERO, SimulationProcessor.vehicleInputs(0.0F, 0.0F));
    }
}
