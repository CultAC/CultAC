package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.Frame;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.transport.CloudConnection;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.List;

/** A socket on {@code terms} that keeps what it is sent, refusing the next {@code refuse} records, holding from a budget. */
final class TestSink implements CaptureStream.Sink {
    final List<byte[]> sent = new ArrayList<>();
    final TestBudget budget;
    CloudConnection.Terms terms = CloudConnection.Terms.sections(0, 0);
    int refuse;
    int maxFrameBytes = 1 << 20;
    boolean textStripped = true;
    /** Bytes held from the budget and not yet released. */
    long used;

    TestSink() {
        this(new TestBudget(TestBudget.MAX_HELD_BYTES));
    }

    TestSink(TestBudget budget) {
        this.budget = budget;
    }

    TestSink(CloudConnection.Terms terms) {
        this();
        this.terms = terms;
    }

    @Override
    public boolean send(byte[] record) {
        if (refuse > 0) {
            refuse--;
            return false;
        }
        sent.add(record);
        return true;
    }

    @Override
    public boolean hold(long bytes) {
        if (!budget.take(bytes)) return false;
        used += bytes;
        return true;
    }

    @Override
    public void release(long bytes) {
        assertTrue(bytes > 0 && bytes <= used, "unbalanced release " + bytes + " from " + used);
        used -= bytes;
        budget.give(bytes);
    }

    @Override
    public long holdRoom() {
        return budget.remaining();
    }

    @Override
    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    @Override
    public CloudConnection.Terms terms() {
        return terms;
    }

    @Override
    public boolean textStripped() {
        return textStripped;
    }

    List<byte[]> entries(IntakeMessage.KindCase kind) {
        return sent.stream()
                .filter(entry -> message(entry).getKindCase() == kind)
                .toList();
    }

    List<Frame> frames() {
        return entries(IntakeMessage.KindCase.FRAME).stream()
                .map(entry -> message(entry).getFrame())
                .toList();
    }

    /** A queued record's one message. */
    static IntakeMessage message(byte[] entry) {
        try {
            return Intake.parseFrom(entry).getMessages(0);
        } catch (InvalidProtocolBufferException malformed) {
            throw new AssertionError(malformed);
        }
    }
}
