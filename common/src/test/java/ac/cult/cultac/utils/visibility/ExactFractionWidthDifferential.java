package ac.cult.cultac.utils.visibility;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Differential check of {@link ExactFraction}'s long terms against {@link ParentExactFraction}, which keeps every
 * value in {@link BigInteger}: each operation on a pair of equal-valued operands must give the same reduced terms,
 * the same raw bound bits, the same returned-reference kind, sign, comparison and exception, in either form.
 */
final class ExactFractionWidthDifferential {
    private static final BigInteger CHUNK = BigInteger.ONE.shiftLeft(30).subtract(BigInteger.ONE);

    /** Counts of what one run compared. */
    static final class Counts {
        long pairs;
        long small;
        long wide;
        long mixed;
        long failures;

        @Override
        public String toString() {
            return pairs + " pairs (" + small + " both long, " + wide + " both wide, " + mixed + " mixed), "
                    + failures + " throwing";
        }
    }

    private ExactFractionWidthDifferential() {}

    /** One value built by the same exact operations in both classes. */
    record Pair(ExactFraction current, ParentExactFraction parent) {}

    static Pair of(double value) {
        return new Pair(ExactFraction.of(value), ParentExactFraction.of(value));
    }

    /** The integer {@code value}, summed from 30-bit chunks that are each an exact double. */
    static Pair integer(BigInteger value) {
        ExactFraction current = ExactFraction.ZERO;
        ParentExactFraction parent = ParentExactFraction.ZERO;
        BigInteger magnitude = value.abs();
        for (int shift = 0; magnitude.bitLength() > shift; shift += 30) {
            long chunk = magnitude.shiftRight(shift).and(CHUNK).longValueExact();
            if (chunk == 0) continue;
            double term = Math.scalb((double) (value.signum() < 0 ? -chunk : chunk), shift);
            current = current.add(ExactFraction.of(term));
            parent = parent.add(ParentExactFraction.of(term));
        }
        return check(new Pair(current, parent), "integer " + value);
    }

    static Pair fraction(BigInteger numerator, BigInteger denominator) {
        Pair top = integer(numerator), bottom = integer(denominator);
        return check(new Pair(top.current.divide(bottom.current), top.parent.divide(bottom.parent)),
                numerator + "/" + denominator);
    }

    /** A random operand: numerator and denominator bit lengths uniform up to 140, dyadic or odd denominators. */
    static Pair random(Random random) {
        int kind = random.nextInt(20);
        if (kind == 0) return of(0.0);
        if (kind == 1) return of(random.nextBoolean() ? 1.0 : -1.0);
        if (kind == 2) return boundary(random);
        if (kind < 9) return geometry(random, kind);
        if (kind < 11) {
            double scale = Math.scalb(1.0, 40 - random.nextInt(130));
            return of((random.nextDouble() - 0.5) * scale);
        }
        BigInteger numerator = bits(random, random.nextInt(141));
        if (numerator.signum() == 0) numerator = BigInteger.ONE;
        if (random.nextBoolean()) numerator = numerator.negate();
        BigInteger denominator;
        int shape = random.nextInt(3);
        if (shape == 0) denominator = BigInteger.ONE.shiftLeft(random.nextInt(141));
        else {
            denominator = bits(random, 1 + random.nextInt(140)).setBit(0);
            if (shape == 2) denominator = denominator.shiftLeft(random.nextInt(70));
        }
        return fraction(numerator, denominator);
    }

    /** Eye-to-box differences as the visibility code forms them, their products and their quotients. */
    static Pair geometry(Random random, int kind) {
        Pair a = difference(random);
        if (kind < 5) return a;
        Pair b = difference(random);
        if (kind < 7) return check(new Pair(a.current.multiply(b.current), a.parent.multiply(b.parent)), "product");
        if (b.parent.signum() == 0) return a;
        return check(new Pair(a.current.divide(b.current), a.parent.divide(b.parent)), "quotient");
    }

    private static Pair difference(Random random) {
        double base = random.nextInt(4) == 0 ? random.nextInt(60_000_000) - 30_000_000 : random.nextInt(2048) - 1024;
        double eye = base + random.nextDouble() * 4 + (random.nextBoolean() ? 1.62F : 0.0);
        double corner = base + random.nextInt(5) + random.nextInt(17) / 16.0D;
        Pair e = of(eye), c = of(corner);
        return check(new Pair(c.current.subtract(e.current), c.parent.subtract(e.parent)), "difference");
    }

    /** Terms at and around the long form's 2^62 limit. */
    static Pair boundary(Random random) {
        BigInteger edge = BigInteger.ONE.shiftLeft(60 + random.nextInt(5)).add(BigInteger.valueOf(random.nextInt(7) - 3));
        if (edge.signum() == 0) edge = BigInteger.ONE;
        BigInteger other = random.nextBoolean()
                ? BigInteger.ONE.shiftLeft(random.nextInt(66))
                : BigInteger.ONE.shiftLeft(60 + random.nextInt(5)).add(BigInteger.valueOf(random.nextInt(7) - 3));
        if (other.signum() <= 0) other = BigInteger.ONE;
        if (random.nextBoolean()) edge = edge.negate();
        return random.nextBoolean() ? fraction(edge, other) : fraction(other, edge.abs());
    }

    private static BigInteger bits(Random random, int length) {
        return length == 0 ? BigInteger.ZERO : new BigInteger(length, random).setBit(length - 1);
    }

    /** Every operation of {@code a} and {@code b} in both classes. */
    static void compareAll(Pair a, Pair b, Counts counts) {
        counts.pairs++;
        boolean as = FractionFields.small(a.current), bs = FractionFields.small(b.current);
        if (as && bs) counts.small++;
        else if (!as && !bs) counts.wide++;
        else counts.mixed++;
        apply(a, b, "add", counts);
        apply(a, b, "subtract", counts);
        apply(a, b, "multiply", counts);
        apply(a, b, "divide", counts);
        apply(a, null, "negate", counts);
        int current = Integer.signum(a.current.compareTo(b.current));
        int parent = Integer.signum(a.parent.compareTo(b.parent));
        if (current != parent) throw new AssertionError("compareTo " + a.parent + " " + b.parent + ": " + current);
        if (a.current.signum() != a.parent.signum()) throw new AssertionError("signum");
    }

    private static Pair apply(Pair a, Pair b, String name, Counts counts) {
        Object current, parent;
        String currentFailure = null, parentFailure = null;
        try {
            current = switch (name) {
                case "add" -> a.current.add(b.current);
                case "subtract" -> a.current.subtract(b.current);
                case "multiply" -> a.current.multiply(b.current);
                case "divide" -> a.current.divide(b.current);
                default -> a.current.negate();
            };
        } catch (RuntimeException e) {
            current = null;
            currentFailure = e.getClass().getName() + ": " + e.getMessage();
        }
        try {
            parent = switch (name) {
                case "add" -> a.parent.add(b.parent);
                case "subtract" -> a.parent.subtract(b.parent);
                case "multiply" -> a.parent.multiply(b.parent);
                case "divide" -> a.parent.divide(b.parent);
                default -> a.parent.negate();
            };
        } catch (RuntimeException e) {
            parent = null;
            parentFailure = e.getClass().getName() + ": " + e.getMessage();
        }
        String what = name + "(" + text(a) + (b == null ? "" : ", " + text(b)) + ")";
        if (!Objects.equals(currentFailure, parentFailure))
            throw new AssertionError(what + ": " + currentFailure + " vs " + parentFailure);
        if (currentFailure != null) {
            counts.failures++;
            return null;
        }
        String currentKind = kind(current, a.current, b == null ? null : b.current, ExactFraction.ZERO, ExactFraction.ONE);
        String parentKind =
                kind(parent, a.parent, b == null ? null : b.parent, ParentExactFraction.ZERO, ParentExactFraction.ONE);
        if (!currentKind.equals(parentKind)) throw new AssertionError(what + " returned " + currentKind + " vs " + parentKind);
        return check(new Pair((ExactFraction) current, (ParentExactFraction) parent), what);
    }

    static Pair check(Pair pair, String what) {
        FractionFields.State current = FractionFields.of(pair.current);
        FractionFields.State parent = FractionFields.of(pair.parent);
        if (!current.equals(parent)) throw new AssertionError(what + ": " + current + " vs " + parent);
        boolean fits = current.numerator().abs().bitLength() <= 62 && current.denominator().bitLength() <= 62;
        if (fits != FractionFields.small(pair.current))
            throw new AssertionError(what + " held " + (fits ? "wide" : "long") + " though it " + (fits ? "fits" : "does not fit"));
        return pair;
    }

    private static String kind(Object result, Object self, Object other, Object zero, Object one) {
        if (result == null) return "null";
        if (result == self) return "self";
        if (result == other) return "other";
        if (result == zero) return "ZERO";
        if (result == one) return "ONE";
        return "new";
    }

    private static String text(Pair pair) {
        FractionFields.State state = FractionFields.of(pair.parent);
        return state.numerator() + "/" + state.denominator();
    }

    /** {@code pairs} random operand pairs, each also against its own sum, product and quotient chains. */
    static Counts randomPairs(long seed, int pairs) {
        Random random = new Random(seed);
        Counts counts = new Counts();
        for (int i = 0; i < pairs; i++) {
            Pair a = random(random), b;
            switch (random.nextInt(8)) {
                case 0 -> b = a;
                case 1 -> b = check(new Pair(a.current.negate().negate(), a.parent.negate().negate()), "equal");
                case 2 -> {
                    Pair step = of(Math.scalb(random.nextBoolean() ? 1.0 : -1.0, -random.nextInt(140)));
                    b = check(new Pair(a.current.add(step.current), a.parent.add(step.parent)), "near");
                }
                default -> b = random(random);
            }
            compareAll(a, b, counts);
            compareAll(b, a, counts);
        }
        return counts;
    }

    /** Operand pairs recorded as hexadecimal {@code n/d n/d} lines, one or two terms per line. */
    static Counts recorded(Path file) throws IOException {
        Counts counts = new Counts();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            String[] parts = line.trim().split(" ");
            List<Pair> values = new ArrayList<>(2);
            for (String part : parts) {
                String[] terms = part.split("/");
                values.add(fraction(new BigInteger(terms[0], 16), new BigInteger(terms[1], 16)));
            }
            Pair a = values.get(0), b = values.size() > 1 ? values.get(1) : values.get(0);
            compareAll(a, b, counts);
            compareAll(b, a, counts);
        }
        return counts;
    }

    /** Runs outside the test framework: {@code <pairs> [seed]}, or operand files. */
    public static void main(String[] args) throws IOException {
        if (args.length > 0 && !args[0].matches("\\d+")) {
            for (String file : args) System.out.println(file + ": " + recorded(Path.of(file)));
            return;
        }
        int pairs = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 1;
        System.out.println(randomPairs(seed, pairs));
    }
}
