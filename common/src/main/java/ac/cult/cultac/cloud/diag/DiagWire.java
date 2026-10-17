package ac.cult.cultac.cloud.diag;

import ac.cult.cultac.cloud.intake.v3.DiagCoverage;
import ac.cult.cultac.cloud.intake.v3.DiagPart;
import ac.cult.cultac.cloud.intake.v3.DiagTick;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import com.google.protobuf.ByteString;

/** The intake protocol's diagnostics messages: a {@code DiagPart} as a queued record. Cold path: generated classes. */
public final class DiagWire {
    public static final int COVERAGE_FULL = DiagCoverage.DIAG_COVERAGE_FULL_VALUE;
    public static final int COVERAGE_PARTIAL = DiagCoverage.DIAG_COVERAGE_PARTIAL_VALUE;
    public static final int COVERAGE_NONE = DiagCoverage.DIAG_COVERAGE_NONE_VALUE;
    /** The version of diagnostics this build offers at connect. */
    public static final int VERSION = 1;

    private DiagWire() {}

    static DiagTick tick(long seq, double offset, Iterable<String> flags, String trace, byte[] full) {
        DiagTick.Builder tick = DiagTick.newBuilder().setSeq(seq).setTrace(trace);
        if (!Double.isNaN(offset)) tick.setOffset(offset);
        tick.addAllFlags(flags);
        if (full != null) tick.setFull(ByteString.copyFrom(full));
        return tick.build();
    }

    /** An {@code Intake} holding one {@code IntakeMessage} of kind {@code diag}: a record the socket queue takes. */
    static byte[] record(DiagPart part) {
        return Intake.newBuilder()
                .addMessages(IntakeMessage.newBuilder().setDiag(part))
                .build()
                .toByteArray();
    }
}
