package ac.cult.cultac.utils.visibility;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/** Exact arithmetic on the binary values supplied by the compensated geometry. */
final class ExactFraction implements Comparable<ExactFraction> {
    static final ExactFraction ZERO = new ExactFraction(BigInteger.ZERO, BigInteger.ONE);
    static final ExactFraction ONE = new ExactFraction(BigInteger.ONE, BigInteger.ONE);
    private final BigInteger numerator;
    private final BigInteger denominator;
    private final double lowerBound;
    private final double upperBound;

    /** The caller supplies coprime terms and a positive denominator. */
    private ExactFraction(BigInteger numerator, BigInteger denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
        double top = numerator.doubleValue();
        double bottom = denominator.doubleValue();
        if (numerator.signum() == 0 || (denominator.equals(BigInteger.ONE) && numerator.bitLength() <= 53)) {
            lowerBound = upperBound = top;
        } else if (!Double.isFinite(top) || !Double.isFinite(bottom)) {
            lowerBound = Double.NEGATIVE_INFINITY;
            upperBound = Double.POSITIVE_INFINITY;
        } else if (top < 0) {
            lowerBound = Math.nextDown(Math.nextDown(top) / Math.nextDown(bottom));
            upperBound = Math.nextUp(Math.nextUp(top) / Math.nextUp(bottom));
        } else {
            lowerBound = Math.nextDown(Math.nextDown(top) / Math.nextUp(bottom));
            upperBound = Math.nextUp(Math.nextUp(top) / Math.nextDown(bottom));
        }
    }

    private ExactFraction(BigInteger numerator, BigInteger denominator, double lowerBound, double upperBound) {
        this.numerator = numerator;
        this.denominator = denominator;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    static ExactFraction of(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite geometry");
        if (value == 0) return ZERO;
        if (value == 1) return ONE;
        long bits = Double.doubleToLongBits(value);
        int exponent = (int) ((bits >>> 52) & 0x7ff);
        long significand = bits & 0x000f_ffff_ffff_ffffL;
        int shift = exponent == 0 ? -1074 : exponent - 1075;
        if (exponent != 0) significand |= 1L << 52;
        // A double's denominator is a power of two. Reduce it with bit shifts,
        // rather than invoking arbitrary-precision GCD for every coordinate.
        int zeros = Long.numberOfTrailingZeros(significand);
        significand >>>= zeros;
        shift += zeros;
        BigInteger numerator = BigInteger.valueOf(significand);
        if (bits < 0) numerator = numerator.negate();
        return shift >= 0
                ? new ExactFraction(numerator.shiftLeft(shift), BigInteger.ONE, value, value)
                : new ExactFraction(numerator, BigInteger.ONE.shiftLeft(-shift), value, value);
    }

    ExactFraction add(ExactFraction other) {
        if (signum() == 0) return other;
        if (other.signum() == 0) return this;
        BigInteger common = gcd(denominator, other.denominator);
        BigInteger left = denominator.divide(common);
        BigInteger right = other.denominator.divide(common);
        BigInteger sum = numerator.multiply(right).add(other.numerator.multiply(left));
        if (sum.signum() == 0) return ZERO;
        // Only the shared denominator factor can divide this sum: the inputs
        // were already reduced. Avoid building and reducing the full product.
        BigInteger reduction = gcd(sum, common);
        return new ExactFraction(sum.divide(reduction), left.multiply(other.denominator.divide(reduction)));
    }

    ExactFraction subtract(ExactFraction other) {
        return add(other.negate());
    }

    ExactFraction multiply(ExactFraction other) {
        if (signum() == 0 || other.signum() == 0) return ZERO;
        if (this == ONE) return other;
        if (other == ONE) return this;
        BigInteger left = gcd(numerator, other.denominator);
        BigInteger right = gcd(other.numerator, denominator);
        return new ExactFraction(
                numerator.divide(left).multiply(other.numerator.divide(right)),
                denominator.divide(right).multiply(other.denominator.divide(left)));
    }

    ExactFraction divide(ExactFraction other) {
        if (other.signum() == 0) throw new ArithmeticException("Zero denominator");
        if (signum() == 0) return ZERO;
        if (other == ONE) return this;
        BigInteger left = numerator.gcd(other.numerator);
        BigInteger right = gcd(other.denominator, denominator);
        BigInteger top = numerator.divide(left).multiply(other.denominator.divide(right));
        BigInteger bottom = denominator.divide(right).multiply(other.numerator.divide(left));
        return bottom.signum() < 0 ? new ExactFraction(top.negate(), bottom.negate()) : new ExactFraction(top, bottom);
    }

    ExactFraction negate() {
        return signum() == 0 ? ZERO : new ExactFraction(numerator.negate(), denominator, -upperBound, -lowerBound);
    }

    double lowerBound() {
        return lowerBound;
    }

    double upperBound() {
        return upperBound;
    }

    private static BigInteger gcd(BigInteger value, BigInteger positive) {
        if (value.signum() == 0) return positive;
        int shift = positive.getLowestSetBit();
        if (positive.bitLength() == shift + 1) {
            int common = Math.min(value.getLowestSetBit(), shift);
            return common == shift ? positive : BigInteger.ONE.shiftLeft(common);
        }
        return value.gcd(positive);
    }

    int signum() {
        return numerator.signum();
    }

    double doubleValue() {
        // BigDecimal avoids infinity/infinity for exact constructions with large denominators.
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), MathContext.DECIMAL128)
                .doubleValue();
    }

    @Override
    public int compareTo(ExactFraction other) {
        if (this == other) return 0;
        if (lowerBound > other.upperBound) return 1;
        if (upperBound < other.lowerBound) return -1;
        if (denominator.equals(other.denominator)) return numerator.compareTo(other.numerator);
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }
}
