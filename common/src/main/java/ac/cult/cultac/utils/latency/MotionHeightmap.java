package ac.cult.cultac.utils.latency;

import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/** One first-available height per column, using PalettedSection's PacketEvents-derived packing. */
final class MotionHeightmap {
    private final int bits;
    private final int perWord;
    private final long mask;
    private final long[] words;

    MotionHeightmap(int height) {
        bits = 32 - Integer.numberOfLeadingZeros(height);
        perWord = Long.SIZE / bits;
        mask = (1L << bits) - 1;
        words = new long[(256 + perWord - 1) / perWord];
    }

    int get(int column) {
        return (int) (words[column / perWord] >>> ((column % perWord) * bits) & mask);
    }

    private void set(int column, int height) {
        int shift = (column % perWord) * bits;
        words[column / perWord] = words[column / perWord] & ~(mask << shift) | ((long) height & mask) << shift;
    }

    // Heightmap.setRawData/primeHeightmaps (26.3): a wrong length primes the supplied
    // sections, leaving existing values for columns where no matching block is found.
    boolean load(long[] received) {
        if (received.length == words.length) {
            System.arraycopy(received, 0, words, 0, words.length);
            return true;
        }
        return false;
    }

    void prime(int scanHeight, IntUnaryOperator stateAt, IntPredicate opaque) {
        for (int column = 0; column < 256; column++) {
            for (int y = scanHeight - 1; y >= 0; y--) {
                int state = stateAt.applyAsInt((y << 8) | column);
                if (state != 0 && opaque.test(state)) {
                    set(column, y + 1);
                    break;
                }
            }
        }
    }

    // Heightmap.update (26.3); heights here are relative to the dimension's minimum Y.
    void update(int column, int y, int state, IntUnaryOperator stateAt, IntPredicate opaque) {
        int firstAvailable = get(column);
        if (y <= firstAvailable - 2) return;
        if (opaque.test(state)) {
            if (y >= firstAvailable) set(column, y + 1);
        } else if (y == firstAvailable - 1) {
            int below = y - 1;
            while (below >= 0 && !opaque.test(stateAt.applyAsInt((below << 8) | column))) below--;
            set(column, below + 1);
        }
    }
}
