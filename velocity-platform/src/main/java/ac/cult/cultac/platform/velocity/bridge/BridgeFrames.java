package ac.cult.cultac.platform.velocity.bridge;

import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage;

/** Size checks against what a backend accepts in one serverbound custom payload. */
public final class BridgeFrames {
    /** Paper disconnects a player whose serverbound custom payload exceeds this many bytes. */
    public static final int MAX_BACKEND_FRAME_BYTES = 32767;

    /** A request id whose VarInt is as long as any real one. */
    public static final int WORST_CASE_REQUEST_ID = Integer.MAX_VALUE;

    /** 8191 UTF-8 characters stay under {@link #MAX_BACKEND_FRAME_BYTES} with the frame's other fields. */
    public static final int MAX_VERBOSE_CHARS = 8191;

    /** The flag id of a FLAG that awaits no verdict. */
    public static final int NOTIFY_FLAG_ID = 0;

    private BridgeFrames() {}

    /** {@code verbose} cut to {@link #MAX_VERBOSE_CHARS}; null becomes empty. */
    public static String capVerbose(String verbose) {
        return cap(verbose, MAX_VERBOSE_CHARS);
    }

    /** {@code text} cut to {@code maxChars}; null becomes empty. */
    public static String cap(String text, int maxChars) {
        if (text == null) return "";
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    /** Whether {@code message} encodes and fits one backend frame, the same test the session applies on write. */
    public static boolean fits(BridgeMessage message) {
        try {
            return BridgeCodec.encodedLength(message) <= MAX_BACKEND_FRAME_BYTES;
        } catch (IllegalArgumentException unencodable) {
            return false;
        }
    }
}
