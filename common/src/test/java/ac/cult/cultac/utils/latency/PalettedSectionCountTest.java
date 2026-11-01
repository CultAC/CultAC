package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PalettedSectionCountTest {
    private static final int REGISTRY = 30_000;

    @Test
    void paletteOrderCountsEqualHashCountsAndMalformedSectionsFailIdentically() {
        var random = new SplittableRandom(0x5EC7L);
        int succeeded = 0, failed = 0, reordered = 0, mismatched = 0, mutated = 0, summed = 0;
        for (int round = 0; round < 6000; round++) {
            boolean blocks = random.nextBoolean();
            int size = blocks ? 4096 : 64;
            int minimumBits = blocks ? 4 : 1;
            int maximumLocalBits = blocks ? 8 : 3;
            int wireBits = random.nextInt(round % 10 == 0 ? maximumLocalBits + 3 : maximumLocalBits + 1);
            int bits = wireBits == 0 ? 0 : wireBits <= maximumLocalBits ? Math.max(minimumBits, wireBits) : 15;
            int codes = bits == 0 || wireBits > maximumLocalBits ? 0 : 1 << bits;
            int paletteSize = codes == 0 ? 1 : 1 + random.nextInt(codes + (bits > 4 ? 8 : 0));
            if (bits <= 4) paletteSize = Math.min(paletteSize, codes == 0 ? 1 : codes);
            boolean malformed = round % 7 == 0;
            int limit = round % 5 == 0 ? REGISTRY / 2 : REGISTRY;
            var version = round % 3 == 0 ? ProtocolVersion.V1_21_4 : ProtocolVersion.V26_3;
            int wordCount = bits == 0 ? 0 : (size + 64 / bits - 1) / (64 / bits);
            boolean wrongLength = version == ProtocolVersion.V1_21_4 && random.nextBoolean();
            var input = Unpooled.buffer();
            try {
                input.writeByte(wireBits);
                if (wireBits <= maximumLocalBits) {
                    if (bits == 0) Wire.writeVarInt(input, random.nextInt(REGISTRY));
                    else {
                        Wire.writeVarInt(input, paletteSize);
                        for (int i = 0; i < paletteSize; i++) Wire.writeVarInt(input, random.nextInt(REGISTRY));
                    }
                }
                if (version == ProtocolVersion.V1_21_4) {
                    int length =
                            !wrongLength ? wordCount : wordCount + (wordCount == 0 || random.nextBoolean() ? 1 : -1);
                    Wire.writeVarInt(input, length);
                    if (wrongLength) {
                        for (int i = 0; i < length; i++) input.writeLong(random.nextLong());
                        mismatched++;
                    }
                }
                if (bits > 0 && !wrongLength) {
                    int range =
                            wireBits > maximumLocalBits ? REGISTRY : malformed ? codes : Math.min(paletteSize, codes);
                    int distinct = 1 + random.nextInt(Math.max(1, range));
                    writeWords(input, size, bits, random, range, distinct);
                }
                var section = blocks
                        ? PalettedSection.readBlocks(input, version, REGISTRY)
                        : PalettedSection.readBiomes(input, version, REGISTRY);
                if (round % 4 == 1) {
                    mutate(section, size, random);
                    mutated++;
                }
                var expected = outcome(section, -1);
                var actual = outcome(section, limit);
                assertEquals(expected.failure, actual.failure, "round " + round);
                long sum = section.weightSumWithin(limit == REGISTRY ? WEIGHTS : HALF_WEIGHTS);
                if (sum >= 0) {
                    assertNull(expected.failure, "round " + round);
                    assertEquals(weighted(totals(expected.calls)), sum, "round " + round);
                    summed++;
                }
                if (expected.failure == null) {
                    assertEquals(totals(expected.calls), totals(actual.calls), "round " + round);
                    assertEquals(expected.calls.size(), actual.calls.size(), "round " + round);
                    succeeded++;
                    if (!expected.calls.equals(actual.calls)) reordered++;
                } else {
                    assertEquals(expected.calls, actual.calls, "round " + round);
                    failed++;
                }
            } finally {
                input.release();
            }
        }
        assertTrue(succeeded > 4000, "succeeded " + succeeded);
        assertTrue(failed > 100, "failed " + failed);
        assertTrue(reordered > 1000, "reordered " + reordered);
        assertTrue(mismatched > 500, "mismatched " + mismatched);
        assertTrue(mutated > 1000, "mutated " + mutated);
        assertTrue(summed > 2000, "summed " + summed);
    }

    private static final long[] WEIGHTS = weights(REGISTRY), HALF_WEIGHTS = weights(REGISTRY / 2);

    private static long[] weights(int states) {
        long[] weights = new long[states];
        for (int state = 0; state < states; state++)
            weights[state] = state % 3 == 0 ? 0 : state % 3 == 1 ? 1 : 1 | 1L << 32;
        return weights;
    }

    private static long weighted(TreeMap<Integer, Integer> totals) {
        long sum = 0;
        for (var entry : totals.entrySet()) sum += WEIGHTS[entry.getKey()] * entry.getValue();
        return sum;
    }

    /** Writes through getAndSet until it rejects a stored code, growing the palette across bit widths. */
    private static void mutate(PalettedSection section, int size, SplittableRandom random) {
        int states = 1 + random.nextInt(size == 4096 ? 400 : 12);
        int base = random.nextInt(REGISTRY - states);
        int writes = 1 + random.nextInt(size == 4096 ? 2000 : 64);
        try {
            for (int i = 0; i < writes; i++) section.getAndSet(random.nextInt(size), base + random.nextInt(states));
        } catch (RuntimeException rejected) {
            // A malformed stored code stops the writes; counting still sees the section as left.
        }
    }

    private record Outcome(List<String> calls, String failure) {}

    private static Outcome outcome(PalettedSection section, int limit) {
        var calls = new ArrayList<String>();
        PalettedSection.CountConsumer consumer = (state, count) -> calls.add(state + ":" + count);
        try {
            if (limit < 0) section.forEachCount(consumer);
            else section.forEachCountWithin(limit, consumer);
            return new Outcome(calls, null);
        } catch (RuntimeException failure) {
            return new Outcome(calls, failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private static TreeMap<Integer, Integer> totals(List<String> calls) {
        var totals = new TreeMap<Integer, Integer>();
        for (String call : calls) {
            int split = call.indexOf(':');
            totals.merge(
                    Integer.parseInt(call.substring(0, split)),
                    Integer.parseInt(call.substring(split + 1)),
                    Integer::sum);
        }
        return totals;
    }

    private static void writeWords(
            ByteBuf output, int size, int bits, SplittableRandom random, int range, int distinct) {
        int perWord = 64 / bits;
        int[] pool = new int[distinct];
        for (int i = 0; i < distinct; i++) pool[i] = random.nextInt(range);
        int index = 0;
        for (int word = 0; word < (size + perWord - 1) / perWord; word++) {
            long value = 0;
            for (int j = 0; j < perWord && index < size; j++, index++)
                value |= (long) pool[random.nextInt(distinct)] << (j * bits);
            output.writeLong(value);
        }
    }
}
