package ac.cult.cultac.player;

import java.security.SecureRandom;

/** A {@link SecureRandom}'s output drawn a block of bytes at a time and read as consecutive big-endian ints. */
final class WireIds {
    static final int BLOCK_BYTES = 256;

    private final byte[] block = new byte[BLOCK_BYTES];
    private int next = BLOCK_BYTES;
    private SecureRandom source;

    /** The next int of {@code random}'s output; a block is drawn when the last is used up or came from another source. */
    synchronized int next(SecureRandom random) {
        if (next == BLOCK_BYTES || random != source) {
            random.nextBytes(block);
            source = random;
            next = 0;
        }
        int value = (block[next] << 24)
                | (block[next + 1] & 0xFF) << 16
                | (block[next + 2] & 0xFF) << 8
                | (block[next + 3] & 0xFF);
        next += 4;
        return value;
    }
}
