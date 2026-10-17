package ac.cult.cultac.cloud.diag;

/**
 * The service's {@code DiagCut}: send the SuperDebug of frames {@code fromSeq..toSeq}, full texts for
 * {@code fullFromSeq..fullToSeq} and for flagged ticks, as bundle {@code bundle} (16 bytes).
 */
public record Cut(byte[] bundle, long fromSeq, long toSeq, long fullFromSeq, long fullToSeq) {
    /** Whether the service could have meant it: a 16-byte id and a range no ring could exceed twice over. */
    boolean valid() {
        return bundle != null
                && bundle.length == 16
                && fromSeq >= 0
                && fromSeq <= toSeq
                && toSeq - fromSeq <= 2L * Limits.MAX_RING_TICKS;
    }
}
