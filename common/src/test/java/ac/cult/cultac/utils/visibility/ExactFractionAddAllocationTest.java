package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Allocation of nonzero same-denominator {@link ExactFraction#add} against the renamed parent copy over the same
 * prebuilt operand values. The budget is 90% of the parent oracle's bytes per add; this guard is not a workload gain.
 */
class ExactFractionAddAllocationTest {
    private static final int OPERANDS = 2048;
    private static final int RETAIN = 256;
    private static final int WARMUP_ROUNDS = 400;
    private static final int MEASURED_ROUNDS = 31;
    private static final double BUDGET = 0.90;
    private static final double MINIMUM_PARENT_BYTES = 32;

    private static final ExactFraction[] CURRENT_SINK = new ExactFraction[RETAIN];
    private static final ParentExactFraction[] PARENT_SINK = new ParentExactFraction[RETAIN];

    @Test
    void sameDenominatorAddAllocatesAtMostNinetyPercentOfParent() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported() || !bean.isThreadAllocatedMemoryEnabled()) {
            fail("thread allocation accounting unsupported or disabled; the allocation gate cannot be evaluated");
        }
        ExactFraction[] currentLeft = new ExactFraction[OPERANDS];
        ExactFraction[] currentRight = new ExactFraction[OPERANDS];
        ParentExactFraction[] parentLeft = new ParentExactFraction[OPERANDS];
        ParentExactFraction[] parentRight = new ParentExactFraction[OPERANDS];
        int identical = build(currentLeft, currentRight, parentLeft, parentRight);

        long currentChecksum = 0;
        long parentChecksum = 0;
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            if ((round & 1) == 0) {
                currentChecksum += runCurrent(currentLeft, currentRight);
                parentChecksum += runParent(parentLeft, parentRight);
            } else {
                parentChecksum += runParent(parentLeft, parentRight);
                currentChecksum += runCurrent(currentLeft, currentRight);
            }
        }
        assertEquals(parentChecksum, currentChecksum, "warm-up results differ from the parent oracle");

        long[] currentBytes = new long[MEASURED_ROUNDS];
        long[] parentBytes = new long[MEASURED_ROUNDS];
        long[] emptyBytes = new long[MEASURED_ROUNDS];
        currentChecksum = 0;
        parentChecksum = 0;
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            long begin = bean.getCurrentThreadAllocatedBytes();
            long end = bean.getCurrentThreadAllocatedBytes();
            emptyBytes[round] = end - begin;
            if ((round & 1) == 0) {
                begin = bean.getCurrentThreadAllocatedBytes();
                currentChecksum += runCurrent(currentLeft, currentRight);
                end = bean.getCurrentThreadAllocatedBytes();
                currentBytes[round] = end - begin;
                begin = bean.getCurrentThreadAllocatedBytes();
                parentChecksum += runParent(parentLeft, parentRight);
                end = bean.getCurrentThreadAllocatedBytes();
                parentBytes[round] = end - begin;
            } else {
                begin = bean.getCurrentThreadAllocatedBytes();
                parentChecksum += runParent(parentLeft, parentRight);
                end = bean.getCurrentThreadAllocatedBytes();
                parentBytes[round] = end - begin;
                begin = bean.getCurrentThreadAllocatedBytes();
                currentChecksum += runCurrent(currentLeft, currentRight);
                end = bean.getCurrentThreadAllocatedBytes();
                currentBytes[round] = end - begin;
            }
        }
        double current = median(currentBytes) / (double) OPERANDS;
        double parent = median(parentBytes) / (double) OPERANDS;
        System.out.println(String.format(Locale.ROOT,
                "FRACTION_ADD_ALLOC currentBytesPerOp=%.3f parentBytesPerOp=%.3f ratio=%.5f operands=%d identicalRefs=%d"
                        + " warmupRounds=%d measuredRounds=%d currentRange=%d..%d parentRange=%d..%d emptyMedian=%d"
                        + " checksum=%d retained=%d",
                current, parent, current / parent, OPERANDS, identical, WARMUP_ROUNDS, MEASURED_ROUNDS,
                min(currentBytes), max(currentBytes), min(parentBytes), max(parentBytes), median(emptyBytes),
                currentChecksum, live()));
        assertEquals(parentChecksum, currentChecksum, "measured results differ from the parent oracle");
        if (parent < MINIMUM_PARENT_BYTES) {
            fail(String.format(Locale.ROOT, "parent oracle allocated %.3f B/op; accounting or escape implausible", parent));
        }
        assertTrue(current <= BUDGET * parent, String.format(Locale.ROOT,
                "current add allocated %.3f B/op over %d same-denominator operands x %d rounds (budget %.3f = 90%% of parent);"
                        + " parent oracle %.3f B/op",
                current, OPERANDS, MEASURED_ROUNDS, BUDGET * parent, parent));
    }

    /** Equal-valued denominators per index: dyadic, larger dyadic, odd and, every fourth, the identical reference. */
    private static int build(ExactFraction[] currentLeft, ExactFraction[] currentRight,
            ParentExactFraction[] parentLeft, ParentExactFraction[] parentRight) {
        Random random = new Random(0xadd0);
        long[] odd = {3, 5, 7, 9, 11, 13, 15, 21, 255, 1_000_003};
        int identical = 0;
        for (int i = 0; i < OPERANDS; i++) {
            int kind = i & 3;
            long a = oddNumber(random, kind == 1 ? 1 << 30 : 1 << 12);
            long b = oddNumber(random, kind == 1 ? 1 << 30 : 1 << 12);
            if (b == -a) b += 2;
            if (kind == 2) {
                long denominator = odd[random.nextInt(odd.length)];
                while (gcd(a, denominator) != 1) a += 2;
                while (gcd(b, denominator) != 1 || b == -a) b += 2;
                currentLeft[i] = ExactFraction.of(a).divide(ExactFraction.of(denominator));
                currentRight[i] = ExactFraction.of(b).divide(ExactFraction.of(denominator));
                parentLeft[i] = ParentExactFraction.of(a).divide(ParentExactFraction.of(denominator));
                parentRight[i] = ParentExactFraction.of(b).divide(ParentExactFraction.of(denominator));
            } else {
                int exponent = 1 + random.nextInt(kind == 1 ? 60 : 24);
                currentLeft[i] = ExactFraction.of(Math.scalb((double) a, -exponent));
                parentLeft[i] = ParentExactFraction.of(Math.scalb((double) a, -exponent));
                if (kind == 3) {
                    currentRight[i] = currentLeft[i];
                    parentRight[i] = parentLeft[i];
                    identical++;
                } else {
                    currentRight[i] = ExactFraction.of(Math.scalb((double) b, -exponent));
                    parentRight[i] = ParentExactFraction.of(Math.scalb((double) b, -exponent));
                }
            }
            assertEquals(FractionFields.of(parentLeft[i]), FractionFields.of(currentLeft[i]));
            assertEquals(FractionFields.of(parentRight[i]), FractionFields.of(currentRight[i]));
            assertTrue(currentLeft[i].signum() != 0 && currentRight[i].signum() != 0, "zero operand " + i);
            assertEquals(FractionFields.denominator(currentLeft[i]), FractionFields.denominator(currentRight[i]),
                    "operand " + i + " denominators differ");
            assertTrue(currentLeft[i].add(currentRight[i]).signum() != 0, "operand " + i + " cancels");
        }
        return identical;
    }

    private static long oddNumber(Random random, int bound) {
        long value = (random.nextInt(bound) << 1) | 1;
        return random.nextBoolean() ? value : -value;
    }

    private static long gcd(long a, long b) {
        a = Math.abs(a);
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    private static long runCurrent(ExactFraction[] left, ExactFraction[] right) {
        long checksum = 0;
        for (int i = 0; i < OPERANDS; i++) {
            ExactFraction sum = left[i].add(right[i]);
            CURRENT_SINK[i & (RETAIN - 1)] = sum;
            checksum = checksum * 31 + sum.signum() + Double.doubleToRawLongBits(sum.lowerBound());
        }
        return checksum;
    }

    private static long runParent(ParentExactFraction[] left, ParentExactFraction[] right) {
        long checksum = 0;
        for (int i = 0; i < OPERANDS; i++) {
            ParentExactFraction sum = left[i].add(right[i]);
            PARENT_SINK[i & (RETAIN - 1)] = sum;
            checksum = checksum * 31 + sum.signum() + Double.doubleToRawLongBits(sum.lowerBound());
        }
        return checksum;
    }

    private static int live() {
        int count = 0;
        for (int i = 0; i < RETAIN; i++) {
            if (CURRENT_SINK[i] != null) count++;
            if (PARENT_SINK[i] != null) count++;
        }
        return count;
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long min(long[] values) {
        return Arrays.stream(values).min().orElseThrow();
    }

    private static long max(long[] values) {
        return Arrays.stream(values).max().orElseThrow();
    }
}
