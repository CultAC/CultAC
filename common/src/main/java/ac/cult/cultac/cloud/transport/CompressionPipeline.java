package ac.cult.cultac.cloud.transport;

import ac.cult.cultac.cloud.wire.IntakeWire;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Writes a connection's messages on a pool of threads shared by every connection and hands them back in the order they
 * were submitted.
 *
 * <p>Only the connection's sender thread submits, takes and drops, so the order is the submission order without any
 * locking; the workers only write. Each message's bytes count against the connection's queued bytes from the moment
 * its records are queued: as records until it is written, then as the written message until {@link #sent} or a drop.
 *
 * <p>On a protocol 5 socket ({@link #stream}) the messages are one zstd frame: each is written after the one submitted
 * before it, one at a time, by the socket's {@link IntakeWire.StreamWriter}, and one whose predecessor failed is not
 * written at all. The socket's writer is closed when the next socket opens and when the pipeline closes.
 */
final class CompressionPipeline implements AutoCloseable {
    /** One per pool thread, which writes one message at a time whichever connection it is for. */
    private static final ThreadLocal<IntakeWire.MessageWriter> WRITERS =
            ThreadLocal.withInitial(IntakeWire.MessageWriter::new);

    private final ExecutorService pool;
    private final QueuedBytes queuedBytes;
    private final int threads;
    private final int depth;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final AtomicInteger writing = new AtomicInteger();
    private final AtomicLong writingBytes = new AtomicLong();
    private final Supplier<IntakeWire.StreamWriter> streams;
    /** The socket's zstd frame on protocol 5; null otherwise. */
    private IntakeWire.StreamWriter stream;
    /** The message last submitted to the stream, which the next one is written after. */
    private CompletableFuture<byte[]> streamTail;

    private record Pending(int bytes, CompletableFuture<byte[]> message) {}

    /**
     * Writes on {@code pool}, which has {@code threads} threads and outlives the pipeline. {@code queuedBytes} is the
     * connection's count of every byte queued or in flight.
     */
    CompressionPipeline(ExecutorService pool, int threads, QueuedBytes queuedBytes) {
        this(pool, threads, queuedBytes, IntakeWire.StreamWriter::new);
    }

    /** As above, with each protocol 5 socket's writer from {@code streams}. */
    CompressionPipeline(
            ExecutorService pool, int threads, QueuedBytes queuedBytes, Supplier<IntakeWire.StreamWriter> streams) {
        this.streams = streams;
        this.pool = pool;
        this.threads = Math.max(1, threads);
        this.depth = this.threads * 2;
        this.queuedBytes = queuedBytes;
    }

    /** A pool of {@code threads} daemon threads named {@code name-i}, for {@link #CompressionPipeline}s to share. */
    static ExecutorService newPool(int threads, String name) {
        return Executors.newFixedThreadPool(
                Math.max(1, threads),
                Thread.ofPlatform().daemon().name(name + "-", 0).factory());
    }

    int threads() {
        return threads;
    }

    /** Whether another message may be submitted; at most two per thread wait to be taken. */
    boolean hasRoom() {
        return pending.size() < depth;
    }

    /** Messages being written right now. */
    int writing() {
        return writing.get();
    }

    /** Record bytes of the messages being written right now. */
    long writingBytes() {
        return writingBytes.get();
    }

    /**
     * A socket opened: on protocol 5 ({@code on}) its messages are one zstd frame from here, written by a fresh
     * {@link IntakeWire.StreamWriter}. Drops what is pending first, then closes the last socket's writer.
     */
    void stream(boolean on) {
        drop();
        // A taken message's write may still be running if its taker was interrupted; zstd refuses to close mid-write.
        if (streamTail != null) streamTail.handle((message, failed) -> null).join();
        if (stream != null) stream.close();
        stream = on ? streams.get() : null;
        streamTail = null;
    }

    /** Whether the socket's messages are one zstd frame, so none may go missing. */
    boolean streaming() {
        return stream != null;
    }

    /** Starts writing {@code records}, {@code bytes} long in all, as one message. The list may be reused after. */
    void submit(List<byte[]> records, int bytes) {
        List<byte[]> own = List.copyOf(records);
        writing.incrementAndGet();
        writingBytes.addAndGet(bytes);
        IntakeWire.StreamWriter writer = stream;
        CompletableFuture<byte[]> message;
        if (writer == null) {
            message = CompletableFuture.supplyAsync(
                    () -> written(() -> WRITERS.get().write(own), bytes), pool);
        } else {
            CompletableFuture<byte[]> after = streamTail == null ? CompletableFuture.completedFuture(null) : streamTail;
            message = after.handleAsync(
                    (previous, failed) -> {
                        if (failed == null) return written(() -> writer.write(own, bytes), bytes);
                        writing.decrementAndGet();
                        writingBytes.addAndGet(-bytes);
                        throw new CompletionException(failed);
                    },
                    pool);
            streamTail = message;
        }
        pending.add(new Pending(bytes, message));
    }

    /** A message written, as its own array: its bytes replace its records' in the queued count. */
    private byte[] written(Supplier<ByteBuffer> write, int bytes) {
        try {
            ByteBuffer written = write.get();
            byte[] out = new byte[written.remaining()];
            written.get(out);
            queuedBytes.addAndGet(out.length - bytes);
            return out;
        } finally {
            writing.decrementAndGet();
            writingBytes.addAndGet(-bytes);
        }
    }

    /**
     * The oldest submitted message, once written; null when none is pending. Its bytes stay queued until {@link #sent}.
     * Interrupted, the message is given up and its bytes leave the count once its write ends.
     */
    byte[] take() throws InterruptedException, ExecutionException {
        Pending head = pending.poll();
        if (head == null) return null;
        try {
            return head.message().get();
        } catch (ExecutionException failed) {
            queuedBytes.addAndGet(-head.bytes());
            throw failed;
        } catch (InterruptedException interrupted) {
            head.message()
                    .whenComplete((message, failed) ->
                            queuedBytes.addAndGet(failed == null ? -message.length : -head.bytes()));
            throw interrupted;
        }
    }

    /** A message from {@link #take} left on the socket or was given up. */
    void sent(byte[] message) {
        queuedBytes.addAndGet(-message.length);
    }

    /** Drops every pending message, waiting out the writes already started so their bytes leave the count. */
    void drop() {
        Pending head;
        while ((head = pending.poll()) != null) {
            try {
                queuedBytes.addAndGet(-head.message().join().length);
            } catch (RuntimeException failed) {
                queuedBytes.addAndGet(-head.bytes());
            }
        }
    }

    /** Drops what is pending and frees the socket's zstd context; the shared pool keeps running. */
    @Override
    public void close() {
        stream(false);
    }
}
