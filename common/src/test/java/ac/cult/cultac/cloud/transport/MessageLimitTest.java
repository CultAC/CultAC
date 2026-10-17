package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.Frame;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Record;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The receiver bounds a message by its bytes with probe markers resolved: {@link CloudConnection#nextMessage} cuts
 * messages at whole entries; neither their record bytes nor their resolved bytes pass the limits unless one entry alone
 * does.
 */
class MessageLimitTest {
    private static final int MAX_MESSAGE_BYTES = 2 * 1024 * 1024;
    /** {@link CloudConnection}'s limit for a connect answer of {@link #MAX_MESSAGE_BYTES}. */
    private static final int MESSAGE_LIMIT = MAX_MESSAGE_BYTES - 1 - MAX_MESSAGE_BYTES / 1024 - 64;

    private final Queue<CloudConnection.Queued> queue = new ConcurrentLinkedQueue<>();
    private final QueuedBytes queuedBytes = new QueuedBytes(new AtomicLong());

    @Test
    void framesThatGrowOnceResolvedAreCutByTheirResolvedBytes() throws InvalidProtocolBufferException {
        long resolvedExtra = 256 * 1024;
        int frames = 40;
        for (int seq = 0; seq < frames; seq++) queue(1, frame(seq, 300), resolvedExtra);
        List<List<byte[]>> messages = drain(1, CloudConnection.MAX_WRITE_BYTES, MESSAGE_LIMIT);
        int seen = 0;
        for (List<byte[]> message : messages) {
            long resolved = 0;
            for (byte[] record : message) resolved += record.length + resolvedExtra;
            assertTrue(resolved <= MESSAGE_LIMIT, "message of " + resolved + " resolved bytes");
            for (IntakeMessage entry : parse(message).getMessagesList()) {
                assertEquals(seen++, entry.getFrame().getSeq());
            }
        }
        assertEquals(frames, seen);
        assertTrue(messages.size() >= frames * resolvedExtra / MESSAGE_LIMIT);
        assertEquals(0, queuedBytes.get());
    }

    @Test
    void recordBytesStopAtTheWriteLimit() throws InvalidProtocolBufferException {
        int frames = 30;
        for (int seq = 0; seq < frames; seq++) queue(1, frame(seq, 100 * 1024), 0);
        List<List<byte[]>> messages = drain(1, CloudConnection.MAX_WRITE_BYTES, MESSAGE_LIMIT);
        int seen = 0;
        for (List<byte[]> message : messages) {
            int bytes = 0;
            for (byte[] record : message) bytes += record.length;
            assertTrue(bytes <= CloudConnection.MAX_WRITE_BYTES);
            seen += parse(message).getMessagesCount();
        }
        assertEquals(frames, seen);
        assertEquals(0, queuedBytes.get());
    }

    @Test
    void anEntryPastEitherLimitGoesAlone() {
        queue(1, frame(0, 10), 0);
        queue(1, frame(1, 10), MESSAGE_LIMIT + 1L);
        queue(1, frame(2, CloudConnection.MAX_WRITE_BYTES + 1), 0);
        queue(1, frame(3, 10), 0);
        List<List<byte[]>> messages = drain(1, CloudConnection.MAX_WRITE_BYTES, MESSAGE_LIMIT);
        assertEquals(List.of(1, 1, 1, 1), messages.stream().map(List::size).toList());
    }

    @Test
    void entriesOfAnotherSocketAreDroppedWithTheirBytes() throws InvalidProtocolBufferException {
        queue(1, frame(0, 10), 0);
        queue(2, frame(1, 10), 0);
        queue(1, frame(2, 10), 0);
        List<List<byte[]>> messages = drain(2, CloudConnection.MAX_WRITE_BYTES, MESSAGE_LIMIT);
        assertEquals(1, messages.size());
        assertEquals(1, parse(messages.get(0)).getMessages(0).getFrame().getSeq());
        assertEquals(0, queuedBytes.get());
        assertTrue(queue.isEmpty());
    }

    private void queue(long generation, byte[] record, long resolvedExtra) {
        queuedBytes.addAndGet(record.length);
        queue.add(new CloudConnection.Queued(generation, record, resolvedExtra));
    }

    /** Every message cut for {@code generation}, its bytes taken off the queued count as the sender does once sent. */
    private List<List<byte[]>> drain(long generation, int limit, long resolvedLimit) {
        List<List<byte[]>> messages = new ArrayList<>();
        while (true) {
            List<byte[]> message = new ArrayList<>();
            int bytes = CloudConnection.nextMessage(queue, queuedBytes, generation, limit, resolvedLimit, message);
            if (bytes == 0) return messages;
            queuedBytes.addAndGet(-bytes);
            messages.add(message);
        }
    }

    /** One {@code Intake.messages} entry: a frame whose one record body is {@code size} bytes. */
    private static byte[] frame(int seq, int size) {
        return Intake.newBuilder()
                .addMessages(IntakeMessage.newBuilder()
                        .setFrame(Frame.newBuilder()
                                .setSeq(seq)
                                .addRecords(Record.newBuilder().setClientbound(ByteString.copyFrom(new byte[size])))))
                .build()
                .toByteArray();
    }

    private static Intake parse(List<byte[]> message) throws InvalidProtocolBufferException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] record : message) out.writeBytes(record);
        return Intake.parseFrom(out.toByteArray());
    }
}
