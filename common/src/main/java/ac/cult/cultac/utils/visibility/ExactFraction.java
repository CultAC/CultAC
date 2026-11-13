package ac.cult.cultac.utils.visibility;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * Exact arithmetic on the binary values supplied by the compensated geometry.
 *
 * <p>A value whose reduced terms both have magnitude under 2^62 is held in {@code num} and {@code den} with the
 * {@link BigInteger} fields null; any other value is held in those fields. Every value has exactly one form, so equal
 * values hold equal terms and equal bounds in either form.
 */
final class ExactFraction implements Comparable<ExactFraction> {
    private static final long LIMIT = 1L << 62;
    static final ExactFraction ZERO = new ExactFraction(0, 1);
    static final ExactFraction ONE = new ExactFraction(1, 1);
    static final ExactFraction MINUS_ONE = new ExactFraction(-1, 1);
    private final long num;
    private final long den;
    private final BigInteger numerator;
    private final BigInteger denominator;
    private final double lowerBound;
    private final double upperBound;

    /** The caller supplies coprime terms in the small range and a positive denominator. */
    private ExactFraction(long num, long den) {
        this.num = num;
        this.den = den;
        this.numerator = null;
        this.denominator = null;
        double top = num;
        double bottom = den;
        if (num == 0 || (den == 1 && bitLength(num) <= 53)) {
            lowerBound = upperBound = top;
        } else if (top < 0) {
            lowerBound = Math.nextDown(Math.nextDown(top) / Math.nextDown(bottom));
            upperBound = Math.nextUp(Math.nextUp(top) / Math.nextUp(bottom));
        } else {
            lowerBound = Math.nextDown(Math.nextDown(top) / Math.nextUp(bottom));
            upperBound = Math.nextUp(Math.nextUp(top) / Math.nextDown(bottom));
        }
    }

    private ExactFraction(long num, long den, double lowerBound, double upperBound) {
        this.num = num;
        this.den = den;
        this.numerator = null;
        this.denominator = null;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    /** The caller supplies coprime terms outside the small range and a positive denominator. */
    private ExactFraction(BigInteger numerator, BigInteger denominator) {
        this.num = 0;
        this.den = 0;
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
        this.num = 0;
        this.den = 0;
        this.numerator = numerator;
        this.denominator = denominator;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    /** Coprime terms with a positive denominator, in whichever form they fit. */
    private static ExactFraction reduced(BigInteger numerator, BigInteger denominator) {
        if (numerator.bitLength() <= 62 && denominator.bitLength() <= 62) {
            long top = numerator.longValue(), bottom = denominator.longValue();
            if (small(top)) return new ExactFraction(top, bottom);
        }
        return new ExactFraction(numerator, denominator);
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
        if (shift >= 0 ? 64 - Long.numberOfLeadingZeros(significand) + shift <= 62 : shift >= -61) {
            long top = bits < 0 ? -significand : significand;
            return shift >= 0
                    ? new ExactFraction(top << shift, 1, value, value)
                    : new ExactFraction(top, 1L << -shift, value, value);
        }
        BigInteger numerator = BigInteger.valueOf(significand);
        if (bits < 0) numerator = numerator.negate();
        return shift >= 0
                ? new ExactFraction(numerator.shiftLeft(shift), BigInteger.ONE, value, value)
                : new ExactFraction(numerator, BigInteger.ONE.shiftLeft(-shift), value, value);
    }

    ExactFraction add(ExactFraction other) {
        if (signum() == 0) return other;
        if (other.signum() == 0) return this;
        if (numerator == null && other.numerator == null) {
            ExactFraction sum = addSmall(other.num, other.den);
            if (sum != null) return sum;
        }
        return wideAdd(other);
    }

    /** This small, nonzero value plus {@code otherNum / otherDen} (small, nonzero), or null when it is not small. */
    private ExactFraction addSmall(long otherNum, long otherDen) {
        if (den == otherDen) {
            long sum = num + otherNum;
            if (sum == 0) return ZERO;
            long reduction = gcd(Math.abs(sum), den);
            long top = sum / reduction;
            return small(top) ? new ExactFraction(top, den / reduction) : null;
        }
        long common = gcd(den, otherDen);
        long left = den / common, right = otherDen / common;
        long high = Math.multiplyHigh(num, right), low = num * right;
        long otherHigh = Math.multiplyHigh(otherNum, left), otherLow = otherNum * left;
        long sum = low + otherLow;
        long carry = Long.compareUnsigned(sum, low) < 0 ? 1 : 0;
        // The two products are under 2^124 in magnitude, so their 128-bit sum cannot wrap.
        if (high + otherHigh + carry != sum >> 63 || !small(sum)) return null;
        if (sum == 0) return ZERO;
        long reduction = gcd(Math.abs(sum), common);
        long rest = otherDen / reduction;
        long bottom = left * rest;
        return Math.multiplyHigh(left, rest) == 0 && bottom > 0 && bottom < LIMIT
                ? new ExactFraction(sum / reduction, bottom)
                : null;
    }

    private ExactFraction wideAdd(ExactFraction other) {
        BigInteger numerator = numerator(), denominator = denominator();
        BigInteger otherNumerator = other.numerator(), otherDenominator = other.denominator();
        if (denominator.equals(otherDenominator)) {
            BigInteger sum = numerator.add(otherNumerator);
            if (sum.signum() == 0) return ZERO;
            BigInteger reduction = gcd(sum, denominator);
            return reduced(exactQuotient(sum, reduction), exactQuotient(denominator, reduction));
        }
        BigInteger common = gcd(denominator, otherDenominator);
        BigInteger left = exactQuotient(denominator, common);
        BigInteger right = exactQuotient(otherDenominator, common);
        BigInteger sum = numerator.multiply(right).add(otherNumerator.multiply(left));
        if (sum.signum() == 0) return ZERO;
        // Only the shared denominator factor can divide this sum: the inputs
        // were already reduced. Avoid building and reducing the full product.
        BigInteger reduction = gcd(sum, common);
        return reduced(exactQuotient(sum, reduction), left.multiply(exactQuotient(otherDenominator, reduction)));
    }

    ExactFraction subtract(ExactFraction other) {
        if (numerator == null && num != 0 && other.numerator == null && other.num != 0) {
            ExactFraction difference = addSmall(-other.num, other.den);
            if (difference != null) return difference;
        }
        return add(other.negate());
    }

    ExactFraction multiply(ExactFraction other) {
        if (signum() == 0 || other.signum() == 0) return ZERO;
        if (this == ONE) return other;
        if (other == ONE) return this;
        if (numerator == null && other.numerator == null) {
            long left = gcd(Math.abs(num), other.den);
            long right = gcd(Math.abs(other.num), den);
            long a = num / left, b = other.num / right;
            long c = den / right, d = other.den / left;
            long top = a * b, bottom = c * d;
            if (Math.multiplyHigh(a, b) == top >> 63
                    && small(top)
                    && Math.multiplyHigh(c, d) == 0
                    && bottom > 0
                    && bottom < LIMIT) return new ExactFraction(top, bottom);
            return reduced(
                    BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)),
                    BigInteger.valueOf(c).multiply(BigInteger.valueOf(d)));
        }
        BigInteger numerator = numerator(), denominator = denominator();
        BigInteger otherNumerator = other.numerator(), otherDenominator = other.denominator();
        BigInteger left = gcd(numerator, otherDenominator);
        BigInteger right = gcd(otherNumerator, denominator);
        return reduced(
                exactQuotient(numerator, left).multiply(exactQuotient(otherNumerator, right)),
                exactQuotient(denominator, right).multiply(exactQuotient(otherDenominator, left)));
    }

    ExactFraction divide(ExactFraction other) {
        if (other.signum() == 0) throw new ArithmeticException("Zero denominator");
        if (signum() == 0) return ZERO;
        if (other == ONE) return this;
        if (numerator == null && other.numerator == null) {
            long left = gcd(Math.abs(num), Math.abs(other.num));
            long right = gcd(other.den, den);
            long a = num / left, b = other.den / right;
            long c = den / right, d = other.num / left;
            if (d < 0) {
                a = -a;
                d = -d;
            }
            long top = a * b, bottom = c * d;
            if (Math.multiplyHigh(a, b) == top >> 63
                    && small(top)
                    && Math.multiplyHigh(c, d) == 0
                    && bottom > 0
                    && bottom < LIMIT) return new ExactFraction(top, bottom);
            return reduced(
                    BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)),
                    BigInteger.valueOf(c).multiply(BigInteger.valueOf(d)));
        }
        BigInteger numerator = numerator(), denominator = denominator();
        BigInteger otherNumerator = other.numerator(), otherDenominator = other.denominator();
        BigInteger left = numerator.gcd(otherNumerator);
        BigInteger right = gcd(otherDenominator, denominator);
        BigInteger top = exactQuotient(numerator, left).multiply(exactQuotient(otherDenominator, right));
        BigInteger bottom = exactQuotient(denominator, right).multiply(exactQuotient(otherNumerator, left));
        return bottom.signum() < 0 ? reduced(top.negate(), bottom.negate()) : reduced(top, bottom);
    }

    ExactFraction negate() {
        if (signum() == 0) return ZERO;
        return numerator == null
                ? new ExactFraction(-num, den, -upperBound, -lowerBound)
                : new ExactFraction(numerator.negate(), denominator, -upperBound, -lowerBound);
    }

    double lowerBound() {
        return lowerBound;
    }

    double upperBound() {
        return upperBound;
    }

    private BigInteger numerator() {
        return numerator != null ? numerator : BigInteger.valueOf(num);
    }

    private BigInteger denominator() {
        return denominator != null ? denominator : BigInteger.valueOf(den);
    }

    private static boolean small(long value) {
        return value > -LIMIT && value < LIMIT;
    }

    /** {@link BigInteger#bitLength()} of {@code value}. */
    private static int bitLength(long value) {
        return 64 - Long.numberOfLeadingZeros(value < 0 ? ~value : value);
    }

    /** Greatest common divisor of a non-negative and a positive value. */
    private static long gcd(long value, long positive) {
        if (value == 0) return positive;
        int shift = Long.numberOfTrailingZeros(value | positive);
        value >>>= Long.numberOfTrailingZeros(value);
        positive >>>= Long.numberOfTrailingZeros(positive);
        while (value != positive) {
            if (value > positive) {
                value -= positive;
                value >>>= Long.numberOfTrailingZeros(value);
            } else {
                positive -= value;
                positive >>>= Long.numberOfTrailingZeros(positive);
            }
        }
        return value << shift;
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

    /** {@code value.divide(divisor)} for a positive divisor that divides {@code value}. */
    private static BigInteger exactQuotient(BigInteger value, BigInteger divisor) {
        int shift = divisor.getLowestSetBit();
        return divisor.signum() > 0 && divisor.bitLength() == shift + 1
                ? value.shiftRight(shift)
                : value.divide(divisor);
    }

    int signum() {
        return numerator == null ? Long.signum(num) : numerator.signum();
    }

    double doubleValue() {
        // BigDecimal avoids infinity/infinity for exact constructions with large denominators.
        return new BigDecimal(numerator())
                .divide(new BigDecimal(denominator()), MathContext.DECIMAL128)
                .doubleValue();
    }

    @Override
    public int compareTo(ExactFraction other) {
        if (this == other) return 0;
        if (lowerBound > other.upperBound) return 1;
        if (upperBound < other.lowerBound) return -1;
        if (numerator == null && other.numerator == null) {
            if (den == other.den) return Long.compare(num, other.num);
            long high = Math.multiplyHigh(num, other.den), low = num * other.den;
            long otherHigh = Math.multiplyHigh(other.num, den), otherLow = other.num * den;
            return high != otherHigh ? Long.compare(high, otherHigh) : Long.compareUnsigned(low, otherLow);
        }
        BigInteger numerator = numerator(), denominator = denominator();
        BigInteger otherNumerator = other.numerator(), otherDenominator = other.denominator();
        if (denominator.equals(otherDenominator)) return numerator.compareTo(otherNumerator);
        return numerator.multiply(otherDenominator).compareTo(otherNumerator.multiply(denominator));
    }
}
