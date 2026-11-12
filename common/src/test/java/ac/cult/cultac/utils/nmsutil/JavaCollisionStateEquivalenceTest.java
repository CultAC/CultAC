package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavaCollisionStateEquivalenceTest {
    private static final double[] DISTANCES = {
        0.0,
        -0.0,
        Double.MIN_VALUE,
        -Double.MIN_VALUE,
        1.0E-9,
        0.5,
        2.5,
        Math.nextUp(2.5),
        3.0,
        1.0E300,
        Double.NaN,
        Double.longBitsToDouble(0x7ff8000000000001L),
        Double.POSITIVE_INFINITY
    };

    @Test
    void everyStateMatchesAFreshRecordBitForBit() {
        for (double distance : DISTANCES) {
            for (int flags = 0; flags < 8; flags++) {
                boolean walks = (flags & 1) != 0;
                boolean descending = (flags & 2) != 0;
                boolean falling = (flags & 4) != 0;
                JavaCollisionState expected = new JavaCollisionState(distance, walks, descending, falling);
                JavaCollisionState actual = JavaCollisionState.of(distance, walks, descending, falling);
                String inputs = distance + " " + flags;
                assertEquals(
                        Double.doubleToRawLongBits(expected.fallDistance()),
                        Double.doubleToRawLongBits(actual.fallDistance()),
                        inputs);
                assertEquals(expected.walksOnPowderSnow(), actual.walksOnPowderSnow(), inputs);
                assertEquals(expected.descending(), actual.descending(), inputs);
                assertEquals(expected.fallingBlock(), actual.fallingBlock(), inputs);
                assertEquals(expected, actual, inputs);
            }
        }
    }
}
