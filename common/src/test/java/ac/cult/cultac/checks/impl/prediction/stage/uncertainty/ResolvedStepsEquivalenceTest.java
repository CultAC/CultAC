package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier.AxisResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier.ResolvedStep;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier.ResolvedSteps;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The per-axis resolved-step index finds what the single linear scan as of ca9de4b40 found, raw bits included. */
class ResolvedStepsEquivalenceTest {
    private static final Collisions.Axis[][] ORDERS = {
        {Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z},
        {Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X},
        {Collisions.Axis.X, Collisions.Axis.Z, Collisions.Axis.Y},
        {Collisions.Axis.Z, Collisions.Axis.X, Collisions.Axis.Y}
    };
    private static final double[] VALUES = {
        0.0D,
        -0.0D,
        Double.NaN,
        Double.longBitsToDouble(0x7ff8000000000001L),
        Double.MIN_VALUE,
        -Double.MIN_VALUE,
        1.0E-7D,
        0.6D,
        -0.0784000015258789D,
        0.5999999865889549D,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Math.nextUp(0.6D),
        64.5D
    };

    /** Probes as the modifier runs them: each axis of each order looked up, recorded on a miss. */
    @Test
    void probeShapedSequencesFindTheSameSteps() {
        SplittableRandom random = new SplittableRandom(11);
        ResolvedSteps index = new ResolvedSteps();
        BaseSteps base = new BaseSteps();
        int hits = 0;
        int lookups = 0;
        long id = 0;
        for (int probe = 0; probe < 200_000; probe++) {
            index.clear();
            base.clear();
            int orders = 1 + random.nextInt(ORDERS.length);
            double[] key = key(random);
            for (int o = 0; o < orders; o++) {
                for (Collisions.Axis axis : ORDERS[o]) {
                    if (random.nextInt(3) == 0) key = mutate(random, key);
                    String want = describe(base.find(axis, key));
                    String got = describe(index.find(axis, key[0], key[1], key[2], key[3], key[4], key[5]));
                    assertEquals(want, got, "probe " + probe);
                    lookups++;
                    if (!want.equals("null")) {
                        hits++;
                        continue;
                    }
                    record(random, index, base, axis, key, id++);
                }
            }
        }
        assertTrue(hits > lookups / 10 && hits < lookups, hits + " hits of " + lookups);
    }

    /** Duplicate keys on one axis: both return the first recorded, and a cleared index forgets everything. */
    @Test
    void duplicatesResolveToTheFirstRecorded() {
        SplittableRandom random = new SplittableRandom(29);
        ResolvedSteps index = new ResolvedSteps();
        BaseSteps base = new BaseSteps();
        long id = 0;
        for (int round = 0; round < 100_000; round++) {
            index.clear();
            base.clear();
            double[][] keys = {key(random), key(random)};
            for (Collisions.Axis axis : Collisions.Axis.values()) {
                int records = random.nextInt(ORDERS.length + 1);
                for (int r = 0; r < records; r++) {
                    record(random, index, base, axis, keys[random.nextInt(2)], id++);
                }
            }
            for (int q = 0; q < 12; q++) {
                Collisions.Axis axis = Collisions.Axis.values()[random.nextInt(3)];
                double[] key = random.nextInt(4) == 0 ? mutate(random, keys[0]) : keys[random.nextInt(2)];
                assertEquals(
                        describe(base.find(axis, key)),
                        describe(index.find(axis, key[0], key[1], key[2], key[3], key[4], key[5])),
                        "round " + round);
            }
        }
    }

    private static void record(
            SplittableRandom random,
            ResolvedSteps index,
            BaseSteps base,
            Collisions.Axis axis,
            double[] key,
            long id) {
        AxisResult pos = random.nextInt(4) == 0 ? null : new AxisResult(value(random), random.nextBoolean());
        AxisResult neg = random.nextInt(4) == 0 ? null : new AxisResult(value(random), random.nextBoolean());
        double movement = value(random);
        double moved = id;
        index.record(axis, key[0], key[1], key[2], key[3], key[4], key[5], pos, neg, movement, moved);
        base.record(axis, key[0], key[1], key[2], key[3], key[4], key[5], pos, neg, movement, moved);
    }

    private static double[] key(SplittableRandom random) {
        double[] key = new double[6];
        for (int i = 0; i < key.length; i++) key[i] = value(random);
        return key;
    }

    private static double[] mutate(SplittableRandom random, double[] key) {
        double[] next = key.clone();
        next[random.nextInt(next.length)] = value(random);
        return next;
    }

    private static double value(SplittableRandom random) {
        return random.nextInt(5) == 0 ? random.nextDouble(-2, 2) : VALUES[random.nextInt(VALUES.length)];
    }

    private static String describe(ResolvedStep step) {
        if (step == null) return "null";
        return axis(step.positive(new AxisResult(0, false))) + " " + axis(step.negative(new AxisResult(0, false)))
                + " " + Double.doubleToRawLongBits(step.movement) + " " + Double.doubleToRawLongBits(step.moved);
    }

    private static String describe(BaseStep step) {
        if (step == null) return "null";
        return axis(step.positive(new AxisResult(0, false))) + " " + axis(step.negative(new AxisResult(0, false)))
                + " " + Double.doubleToRawLongBits(step.movement) + " " + Double.doubleToRawLongBits(step.moved);
    }

    private static String axis(AxisResult result) {
        return result == null ? "-" : result.isCollide() + "/" + Double.doubleToRawLongBits(result.getResult());
    }

    /** CollisionModifier's findResolvedStep and recordResolvedStep as of ca9de4b40. */
    private static final class BaseSteps {
        private final BaseStep[] resolvedSteps = new BaseStep[ORDERS.length * 3];
        private int resolvedStepCount;

        void clear() {
            resolvedStepCount = 0;
        }

        BaseStep find(Collisions.Axis axis, double[] key) {
            return findResolvedStep(axis, key[0], key[1], key[2], key[3], key[4], key[5]);
        }

        private BaseStep findResolvedStep(
                Collisions.Axis axis,
                double x,
                double y,
                double z,
                double positiveMovement,
                double negativeMovement,
                double movement) {
            long xBits = Double.doubleToRawLongBits(x);
            long yBits = Double.doubleToRawLongBits(y);
            long zBits = Double.doubleToRawLongBits(z);
            long positiveBits = Double.doubleToRawLongBits(positiveMovement);
            long negativeBits = Double.doubleToRawLongBits(negativeMovement);
            long movementBits = Double.doubleToRawLongBits(movement);
            for (int i = 0; i < resolvedStepCount; i++) {
                BaseStep step = resolvedSteps[i];
                if (step.axis == axis
                        && step.x == xBits
                        && step.y == yBits
                        && step.z == zBits
                        && step.positiveMovement == positiveBits
                        && step.negativeMovement == negativeBits
                        && step.requestedMovement == movementBits) {
                    return step;
                }
            }
            return null;
        }

        void record(
                Collisions.Axis axis,
                double x,
                double y,
                double z,
                double positiveMovement,
                double negativeMovement,
                double requestedMovement,
                AxisResult pos,
                AxisResult neg,
                double movement,
                double moved) {
            if (resolvedStepCount == resolvedSteps.length) return;
            BaseStep step = resolvedSteps[resolvedStepCount];
            if (step == null) {
                step = new BaseStep();
                resolvedSteps[resolvedStepCount] = step;
            }
            resolvedStepCount++;
            step.axis = axis;
            step.x = Double.doubleToRawLongBits(x);
            step.y = Double.doubleToRawLongBits(y);
            step.z = Double.doubleToRawLongBits(z);
            step.positiveMovement = Double.doubleToRawLongBits(positiveMovement);
            step.negativeMovement = Double.doubleToRawLongBits(negativeMovement);
            step.requestedMovement = Double.doubleToRawLongBits(requestedMovement);
            step.hasPositive = pos != null;
            step.positiveCollide = pos != null && pos.isCollide();
            step.positiveResult = pos == null ? 0 : pos.getResult();
            step.hasNegative = neg != null;
            step.negativeCollide = neg != null && neg.isCollide();
            step.negativeResult = neg == null ? 0 : neg.getResult();
            step.movement = movement;
            step.moved = moved;
        }
    }

    private static final class BaseStep {
        private Collisions.Axis axis;
        private long x, y, z;
        private long positiveMovement, negativeMovement, requestedMovement;
        private boolean hasPositive, positiveCollide, hasNegative, negativeCollide;
        private double positiveResult, negativeResult;
        private double movement, moved;

        private AxisResult positive(AxisResult out) {
            return restore(out, hasPositive, positiveCollide, positiveResult);
        }

        private AxisResult negative(AxisResult out) {
            return restore(out, hasNegative, negativeCollide, negativeResult);
        }

        private static AxisResult restore(AxisResult out, boolean present, boolean collide, double result) {
            if (!present) return null;
            out.setResult(result);
            out.setCollide(collide);
            return out;
        }
    }
}
