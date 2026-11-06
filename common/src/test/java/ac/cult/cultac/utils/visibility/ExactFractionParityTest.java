package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Exact parity of {@link ExactFraction} against the renamed parent copy {@link ParentExactFraction}: reduced terms,
 * raw bound bits, returned-reference kind, sign, comparison and exception class/message after every operation.
 */
class ExactFractionParityTest {
    private static final long[] ODD = {3, 5, 7, 9, 11, 13, 15, 21, 255, 1_000_003};
    private static final double[] EDGES = {
        0.0,
        -0.0,
        1,
        -1,
        .1,
        -.1,
        .5,
        Double.MIN_VALUE,
        -Double.MIN_VALUE,
        2 * Double.MIN_VALUE,
        Double.MIN_NORMAL,
        Math.nextDown(Double.MIN_NORMAL),
        -Math.nextDown(Double.MIN_NORMAL),
        Double.MAX_VALUE,
        -Double.MAX_VALUE,
        Math.scalb(1.0, 1023),
        Math.scalb(1.0, -1022),
        Math.scalb(1.0, 52) + 1,
        Math.scalb(1.0, 53),
        -30_000_000.000001,
        1e300,
        -1e-300,
        1.62F
    };

    @Test
    void factoryMatchesParentForEdgeDoubles() {
        for (double value : EDGES) Twin.of(value);
        Random random = new Random(0xfa01);
        for (int i = 0; i < 4096; i++) {
            double value = Double.longBitsToDouble(random.nextLong());
            if (Double.isFinite(value)) Twin.of(value);
        }
    }

    @Test
    void nonFiniteFactoryIsRejectedLikeParent() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            Outcome current = Outcome.of(() -> ExactFraction.of(value));
            Outcome parent = Outcome.of(() -> ParentExactFraction.of(value));
            assertEquals("java.lang.IllegalArgumentException: Non-finite geometry", current.failure);
            assertEquals(parent.failure, current.failure);
        }
    }

    @Test
    void equalDyadicDenominatorsMatchParent() {
        for (int exponent = 1; exponent <= 62; exponent++) {
            for (long left : new long[] {1, -1, 3, -5, 7, 1_048_575, -(1L << 40) - 1}) {
                for (long right : new long[] {1, -1, 5, -3, 9, 999_999, (1L << 41) + 3}) {
                    Twin a = Twin.of(Math.scalb((double) left, -exponent));
                    Twin b = Twin.of(Math.scalb((double) right, -exponent));
                    a.add(b);
                    a.subtract(b);
                    b.add(a);
                }
            }
        }
        assertEquals(BigInteger.ONE.shiftLeft(1), Twin.of(.25).add(Twin.of(.25)).denominator());
        assertEquals("1/1", Twin.of(.375).add(Twin.of(.625)).toString());
        assertSame(ExactFraction.ZERO, Twin.of(.375).add(Twin.of(-.375)).current);
    }

    @Test
    void equalOddDenominatorsMatchParent() {
        int distinctEqual = 0;
        for (long denominator : ODD) {
            for (long left = -12; left <= 12; left++) {
                for (long right = -12; right <= 12; right++) {
                    Twin a = Twin.fraction(left, denominator);
                    Twin b = Twin.fraction(right, denominator);
                    if (a.signum() != 0 && b.signum() != 0 && a.denominator() != b.denominator()
                            && a.denominator().equals(b.denominator())) distinctEqual++;
                    a.add(b);
                    a.subtract(b);
                }
            }
        }
        assertEquals(BigInteger.valueOf(3), Twin.fraction(1, 6).add(Twin.fraction(1, 6)).denominator());
        assertEquals(BigInteger.valueOf(3), Twin.fraction(1, 15).add(Twin.fraction(4, 15)).denominator());
        assertEquals("1/1", Twin.fraction(5, 9).add(Twin.fraction(4, 9)).toString());
        assertTrue(distinctEqual > 0, "equal denominators in distinct references: " + distinctEqual);
        Twin mixed = Twin.fraction(1, 12);
        mixed.add(Twin.fraction(5, 12));
        mixed.add(Twin.fraction(7, 12));
        mixed.add(Twin.fraction(-1, 12));
    }

    @Test
    void identicalDenominatorReferencesMatchParent() {
        for (double value : EDGES) {
            Twin a = Twin.of(value);
            if (a.signum() == 0) continue;
            Twin negated = a.negate();
            assertSame(a.denominator(), negated.denominator());
            a.add(a);
            a.add(negated);
            a.subtract(a);
            negated.add(negated.negate());
        }
        for (long denominator : ODD) {
            Twin a = Twin.fraction(7, denominator);
            assertSame(a.denominator(), a.negate().denominator());
            a.add(a);
            a.add(a.negate());
            a.add(a.negate().negate());
        }
        assertSame(ExactFraction.ZERO, Twin.of(.1).add(Twin.of(.1).negate()).current);
    }

    @Test
    void unequalDenominatorsMatchParent() {
        Twin[] values = {
            Twin.of(3),
            Twin.of(-7),
            Twin.of(.5),
            Twin.of(.1),
            Twin.of(Math.scalb(3.0, -40)),
            Twin.fraction(1, 3),
            Twin.fraction(-2, 9),
            Twin.fraction(5, 12),
            Twin.fraction(7, 1_000_003),
            Twin.fraction(1, 6)
        };
        for (Twin a : values) {
            for (Twin b : values) {
                a.add(b);
                a.subtract(b);
                a.multiply(b);
                a.divide(b);
                a.compare(b);
            }
        }
    }

    @Test
    void extremesAndSubnormalsMatchParent() {
        Twin min = Twin.of(Double.MIN_VALUE);
        Twin max = Twin.of(Double.MAX_VALUE);
        min.add(min);
        min.add(Twin.of(Math.scalb(1.0, -1073)));
        min.add(Twin.of(1));
        max.add(max);
        max.add(max.negate());
        max.add(min);
        Twin.of(-Double.MAX_VALUE).add(max);
        Twin tiny = min.divide(Twin.of(3));
        tiny.add(tiny);
        tiny.add(min.divide(Twin.of(-3)));
        tiny.add(Twin.of(Double.MIN_NORMAL));
        Twin huge = max.multiply(max).divide(Twin.of(7));
        huge.add(huge);
        huge.add(Twin.fraction(1, 7));
        huge.compare(max);
        tiny.compare(min);
    }

    @Test
    void zeroAndNullFollowParentEvaluationOrder() {
        assertNull(ExactFraction.ZERO.add(null));
        assertNull(ParentExactFraction.ZERO.add(null));
        Twin x = Twin.fraction(5, 3);
        assertSame(x.current, ExactFraction.ZERO.add(x.current));
        assertSame(x.parent, ParentExactFraction.ZERO.add(x.parent));
        assertSame(x.current, x.current.add(ExactFraction.ZERO));
        assertSame(x.parent, x.parent.add(ParentExactFraction.ZERO));
        assertSame(ExactFraction.ZERO, ExactFraction.ZERO.add(ExactFraction.ZERO));
        assertSame(ParentExactFraction.ZERO, ParentExactFraction.ZERO.add(ParentExactFraction.ZERO));
        Outcome current = Outcome.of(() -> x.current.add(null));
        Outcome parent = Outcome.of(() -> x.parent.add(null));
        assertTrue(current.failure.startsWith("java.lang.NullPointerException"), current.failure);
        assertEquals(parent.failure, current.failure);
        assertEquals(Outcome.of(() -> ParentExactFraction.ZERO.subtract(null)).failure,
                Outcome.of(() -> ExactFraction.ZERO.subtract(null)).failure);
        Twin zero = Twin.of(0);
        zero.add(x);
        x.add(zero);
        zero.add(zero);
        zero.subtract(x);
    }

    @Test
    void divisionFailuresAndSignsMatchParent() {
        Twin x = Twin.fraction(-4, 9);
        x.divide(Twin.of(0));
        Twin.of(0).divide(Twin.of(0));
        x.divide(x.negate());
        x.divide(Twin.of(1));
        Twin.of(0).divide(x);
        x.compare(x);
        x.compare(x.negate());
        x.compare(Twin.fraction(-8, 18));
    }

    @Test
    void randomOperandsMatchParent() {
        Random random = new Random(0xadd5);
        for (int i = 0; i < 20_000; i++) {
            Twin a = random(random);
            Twin b = random.nextInt(4) == 0 ? sameDenominator(random, a) : random(random);
            a.add(b);
            a.subtract(b);
            a.compare(b);
            if ((i & 7) == 0) {
                a.multiply(b);
                a.divide(b);
            }
        }
    }

    @Test
    void randomSequencesMatchParent() {
        Random random = new Random(0x5e9);
        for (int sequence = 0; sequence < 300; sequence++) {
            Twin sum = Twin.of(0);
            for (int step = 0; step < 64; step++) {
                Twin term = random.nextInt(3) == 0 ? sameDenominator(random, sum) : random(random);
                Twin next = random.nextBoolean() ? sum.add(term) : sum.subtract(term);
                next.compare(sum);
                sum = next;
            }
        }
    }

    /** A term with exactly {@code like}'s reduced denominator when one exists; equal values, distinct references. */
    private static Twin sameDenominator(Random random, Twin like) {
        if (like.signum() == 0) return random(random);
        BigInteger denominator = like.denominator();
        for (int attempt = 0; attempt < 8; attempt++) {
            long numerator = random.nextInt(4097) - 2048;
            if (numerator == 0 || !BigInteger.valueOf(numerator).gcd(denominator).equals(BigInteger.ONE)) continue;
            if (denominator.bitLength() <= 53) return Twin.fraction(numerator, denominator.longValueExact());
            if (denominator.getLowestSetBit() == denominator.bitLength() - 1 && denominator.bitLength() <= 1075) {
                return Twin.of(Math.scalb((double) numerator, -(denominator.bitLength() - 1)));
            }
        }
        return random(random);
    }

    private static Twin random(Random random) {
        switch (random.nextInt(6)) {
            case 0:
                return Twin.of(Math.scalb((double) (random.nextInt(1 << 21) - (1 << 20)), -random.nextInt(61)));
            case 1:
                return Twin.fraction(random.nextInt(2049) - 1024, ODD[random.nextInt(ODD.length)]);
            case 2:
                return Twin.fraction(random.nextInt(2049) - 1024, ODD[random.nextInt(ODD.length)] << random.nextInt(20));
            case 3:
                return Twin.of(random.nextInt(64) - 32 + random.nextDouble() + 1.62F);
            case 4:
                return Twin.of(random.nextInt(64) - 32 + random.nextInt(17) / 16.0D);
            default:
                return Twin.of(EDGES[random.nextInt(EDGES.length)]);
        }
    }

    /** One value built identically in both classes; every operation checks parity of its result before returning it. */
    private static final class Twin {
        final ExactFraction current;
        final ParentExactFraction parent;

        private Twin(ExactFraction current, ParentExactFraction parent) {
            this.current = current;
            this.parent = parent;
        }

        static Twin of(double value) {
            Outcome c = Outcome.of(() -> ExactFraction.of(value));
            Outcome p = Outcome.of(() -> ParentExactFraction.of(value));
            return checked(c, p, null, null, "of(" + value + ")");
        }

        static Twin fraction(long numerator, long denominator) {
            return of(numerator).divide(of(denominator));
        }

        Twin add(Twin other) {
            return apply(other, ExactFraction::add, ParentExactFraction::add, "add");
        }

        Twin subtract(Twin other) {
            return apply(other, ExactFraction::subtract, ParentExactFraction::subtract, "subtract");
        }

        Twin multiply(Twin other) {
            return apply(other, ExactFraction::multiply, ParentExactFraction::multiply, "multiply");
        }

        Twin divide(Twin other) {
            return apply(other, ExactFraction::divide, ParentExactFraction::divide, "divide");
        }

        Twin negate() {
            Outcome c = Outcome.of(current::negate);
            Outcome p = Outcome.of(parent::negate);
            return checked(c, p, this, null, this + ".negate()");
        }

        void compare(Twin other) {
            assertEquals(Integer.signum(parent.compareTo(other.parent)), Integer.signum(current.compareTo(other.current)),
                    this + " compareTo " + other);
            assertEquals(parent.signum(), current.signum());
            assertEquals(Double.doubleToRawLongBits(parent.doubleValue()), Double.doubleToRawLongBits(current.doubleValue()),
                    "doubleValue of " + this);
        }

        int signum() {
            return current.signum();
        }

        BigInteger denominator() {
            return FractionFields.of(current).denominator();
        }

        private Twin apply(Twin other, BiFunction<ExactFraction, ExactFraction, ExactFraction> currentOp,
                BiFunction<ParentExactFraction, ParentExactFraction, ParentExactFraction> parentOp, String name) {
            Outcome c = Outcome.of(() -> currentOp.apply(current, other.current));
            Outcome p = Outcome.of(() -> parentOp.apply(parent, other.parent));
            Twin result = checked(c, p, this, other, this + "." + name + "(" + other + ")");
            if (result != null) result.compare(this);
            return result == null ? this : result;
        }

        private static Twin checked(Outcome current, Outcome parent, Twin self, Twin other, String what) {
            assertEquals(parent.failure, current.failure, what);
            if (current.failure != null) return null;
            ExactFraction c = (ExactFraction) current.value;
            ParentExactFraction p = (ParentExactFraction) parent.value;
            assertEquals(kind(p, self == null ? null : self.parent, other == null ? null : other.parent,
                            ParentExactFraction.ZERO, ParentExactFraction.ONE),
                    kind(c, self == null ? null : self.current, other == null ? null : other.current,
                            ExactFraction.ZERO, ExactFraction.ONE), "returned reference of " + what);
            FractionFields.State state = FractionFields.of(c);
            assertEquals(FractionFields.of(p), state, what);
            assertTrue(state.denominator().signum() > 0, what);
            assertEquals(BigInteger.ONE, state.numerator().gcd(state.denominator()), what);
            assertTrue(c.lowerBound() <= c.upperBound(), what);
            return new Twin(c, p);
        }

        private static String kind(Object result, Object self, Object other, Object zero, Object one) {
            if (result == null) return "null";
            if (result == self) return "self";
            if (result == other) return "other";
            if (result == zero) return "ZERO";
            if (result == one) return "ONE";
            return "new";
        }

        @Override
        public String toString() {
            FractionFields.State state = FractionFields.of(current);
            return state.numerator() + "/" + state.denominator();
        }
    }

    private static final class Outcome {
        final Object value;
        final String failure;

        private Outcome(Object value, String failure) {
            this.value = value;
            this.failure = failure;
        }

        static Outcome of(Supplier<?> call) {
            try {
                return new Outcome(call.get(), null);
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? "" : e.getMessage().replace("ParentExactFraction", "ExactFraction");
                return new Outcome(null, e.getClass().getName() + ": " + message);
            }
        }
    }
}
