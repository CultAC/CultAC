package ac.cult.cultac.cloud.diag;

import ac.cult.cultac.checks.impl.prediction.BlockGrid;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A tick's full text as one Zstandard frame: its state as the packet thread printed it, then the block grid, in UTF-8.
 * The text, its bytes and the frame are built in buffers kept between ticks, so only the frame's exact copy is new; a
 * text past {@link #KEPT_CHARS} or with a char outside ASCII goes through a string of its own. Used by one diagnostics
 * thread at a time.
 */
final class DiagText implements AutoCloseable {
    /** The longest text built in the kept buffers. */
    static final int KEPT_CHARS = 256 * 1024;

    private static final int INITIAL_CHARS = 16 * 1024;

    private final ZstdCompressCtx zstd;
    private StringBuilder text = new StringBuilder(INITIAL_CHARS);
    private char[] chars = new char[0];
    private byte[] bytes = new byte[0];
    private byte[] frame = new byte[0];
    private int length;

    DiagText(int level) {
        zstd = new ZstdCompressCtx().setLevel(level);
    }

    byte[] compress(String state, BlockGrid grid) {
        StringBuilder text = this.text;
        text.setLength(0);
        text.append(state);
        if (grid != null) grid.appendTo(text);
        length = text.length();
        if (length > KEPT_CHARS || !ascii(text)) {
            this.text = new StringBuilder(INITIAL_CHARS);
            return zstd.compress(text.toString().getBytes(StandardCharsets.UTF_8));
        }
        int bound = (int) Zstd.compressBound(length);
        if (frame.length < bound) frame = new byte[bound];
        return Arrays.copyOf(frame, zstd.compressByteArray(frame, 0, frame.length, bytes, 0, length));
    }

    /** The chars of the last text compressed. */
    int length() {
        return length;
    }

    /** Copies the text into {@link #bytes}, one byte per char, while every char is ASCII, which UTF-8 keeps as is. */
    private boolean ascii(StringBuilder text) {
        if (chars.length < length) {
            int size = Math.min(KEPT_CHARS, Math.max(length, chars.length * 2));
            chars = new char[size];
            bytes = new byte[size];
        }
        text.getChars(0, length, chars, 0);
        for (int i = 0; i < length; i++) {
            char c = chars[i];
            if (c >= 0x80) return false;
            bytes[i] = (byte) c;
        }
        return true;
    }

    @Override
    public void close() {
        zstd.close();
    }
}
