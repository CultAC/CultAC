package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.PerfOutcome;
import ac.cult.cultac.cloud.intake.v3.PerfPart;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.util.UUID;

/** The intake protocol's perf messages: a {@code PerfPart} as a queued record. Cold path: generated classes. */
public final class PerfWire {
    /** The version of perf this build offers at connect. */
    public static final int VERSION = 1;

    private PerfWire() {}

    static ByteString uuid(UUID profile) {
        return ByteString.copyFrom(ByteBuffer.allocate(16)
                .putLong(profile.getMostSignificantBits())
                .putLong(profile.getLeastSignificantBits())
                .array());
    }

    /** A part of {@code profile} carrying {@code data}; a last part also carries {@code outcome} and {@code reason}. */
    static PerfPart part(UUID profile, int index, ByteString data, boolean last, PerfOutcome outcome, String reason) {
        PerfPart.Builder part =
                PerfPart.newBuilder().setProfile(uuid(profile)).setIndex(index).setData(data);
        if (last) part.setLast(true).setOutcome(outcome).setReason(reason);
        return part.build();
    }

    /** An {@code Intake} holding one {@code IntakeMessage} of kind {@code perf}: a record the socket queue takes. */
    static byte[] record(PerfPart part) {
        return Intake.newBuilder()
                .addMessages(IntakeMessage.newBuilder().setPerf(part))
                .build()
                .toByteArray();
    }
}
