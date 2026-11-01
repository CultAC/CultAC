package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.Frame;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.wire.IntakeWire;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The receiver bounds a message by its bytes with probe markers resolved: {@link CloudConnection#nextPart} cuts
 * messages at whole entries; neither their record bytes nor their resolved bytes pass the limits unless one entry alone
 * does.
 */
class MessageLimitTest {
    private static final int MAX_MESSAGE_BYTES = 2 * 1024 * 1024;
    /** {@link CloudConnection}'s limit for a connect answer of {@link #MAX_MESSAGE_BYTES}. */
    private static final int MESSAGE_LIMIT = MAX_MESSAGE_BYTES - 1 - MAX_MESSAGE_BYTES / 1024 - 64;

    private final Queue<CloudConnection.Queued> queue = new ConcurrentLinkedQueue<>();
    private final QueuedBytes queuedBytes = new QueuedBytes(new AtomicLong());
    private final List<CloudConnection.Queued> incoming = new ArrayList<>();

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
    void aJoinALeaveOrTeamSyncEndsTheMessageThatTakesIt() {
        UUID player = new UUID(1, 2);
        queue(1, frame(0, 10), 0);
        queue(1, IntakeWire.join(player, 773, "vanilla", -64, 384, null, null, 0), 0);
        queue(1, frame(1, 10), 0);
        queue(1, frame(2, 10), 0);
        queue(1, IntakeWire.leave(player), 0);
        queue(1, IntakeWire.team(List.of()), 0);
        queue(1, frame(3, 10), 0);
        List<List<byte[]>> messages = drain(1, CloudConnection.MAX_WRITE_BYTES, MESSAGE_LIMIT);
        assertEquals(List.of(2, 3, 1, 1), messages.stream().map(List::size).toList());
        assertEquals(0, queuedBytes.get());
    }

    @Test
    void aJoinThatDoesNotFitEndsTheOpenMessageEmptyThenItsOwn() {
        byte[] frame = frame(0, 1000);
        byte[] join = IntakeWire.join(new UUID(1, 2), 773, "vanilla", -64, 384, null, null, 0);
        int limit = frame.length + join.length - 1;
        CloudConnection.Batch batch = new CloudConnection.Batch();
        List<byte[]> part = new ArrayList<>();
        queuedBytes.addAndGet(frame.length);
        queue.add(new CloudConnection.Queued(1, frame, 0));
        assertEquals(frame.length, CloudConnection.nextPart(queue, queuedBytes, 1, limit, MESSAGE_LIMIT, batch, part));
        assertFalse(batch.full, "the frame leaves the message open");
        part.clear();

        queuedBytes.addAndGet(join.length);
        queue.add(new CloudConnection.Queued(1, join, 0));
        assertEquals(0, CloudConnection.nextPart(queue, queuedBytes, 1, limit, MESSAGE_LIMIT, batch, part));
        assertTrue(batch.full, "the JOIN does not fit: the open message ends with an empty part");
        assertTrue(part.isEmpty());
        batch.clear();

        assertEquals(join.length, CloudConnection.nextPart(queue, queuedBytes, 1, limit, MESSAGE_LIMIT, batch, part));
        assertTrue(batch.full, "the JOIN ends the next message, which it opens");
        assertEquals(List.of(join), part);
        assertTrue(queue.isEmpty());
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
        incoming.add(new CloudConnection.Queued(generation, record, resolvedExtra));
    }

    /**
     * Every message cut for {@code generation}, the entries queued one at a time and each message gathered in parts as
     * the sender does, its bytes taken off the queued count as the sender does once sent.
     */
    private List<List<byte[]>> drain(long generation, int limit, long resolvedLimit) {
        List<List<byte[]>> messages = new ArrayList<>();
        List<byte[]> message = new ArrayList<>();
        CloudConnection.Batch batch = new CloudConnection.Batch();
        for (CloudConnection.Queued entry : incoming) {
            queuedBytes.addAndGet(entry.length());
            queue.add(entry);
            while (true) {
                List<byte[]> part = new ArrayList<>();
                int bytes = CloudConnection.nextPart(queue, queuedBytes, generation, limit, resolvedLimit, batch, part);
                queuedBytes.addAndGet(-bytes);
                message.addAll(part);
                if (!batch.full) break;
                messages.add(message);
                message = new ArrayList<>();
                batch.clear();
            }
        }
        if (!message.isEmpty()) messages.add(message);
        incoming.clear();
        return messages;
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
