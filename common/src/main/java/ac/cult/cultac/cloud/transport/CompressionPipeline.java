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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>A socket's messages ({@link #stream}) are one zstd frame: each is written after the one submitted before it, one at
 * a time, by the socket's {@link IntakeWire.StreamWriter}, and one whose predecessor failed is not written at all. The
 * socket's writer is closed when the next socket opens and when the pipeline closes.
 *
 * <p>A message may be submitted in parts, all but the last leaving it open: each part is compressed as it comes, its
 * records let go, and only its compressed bytes are held, off-heap, until the last part ends the message.
 */
final class CompressionPipeline implements AutoCloseable {
    private final ExecutorService pool;
    private final QueuedBytes queuedBytes;
    private final int threads;
    private final int depth;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final AtomicInteger writing = new AtomicInteger();
    private final AtomicLong writingBytes = new AtomicLong();
    private final Supplier<IntakeWire.StreamWriter> streams;
    /** The socket's zstd frame; null before the first socket and once closed. */
    private IntakeWire.StreamWriter stream;
    /** The part last submitted to the stream, which the next one is written after. */
    private CompletableFuture<byte[]> streamTail;
    /** What the open message holds in the queued count; null while no message is open. */
    private AtomicLong open;

    /** A message ended; {@code counted} is what it holds in the queued count until it is written. */
    private record Pending(AtomicLong counted, CompletableFuture<byte[]> message) {}

    /**
     * Writes on {@code pool}, which has {@code threads} threads and outlives the pipeline. {@code queuedBytes} is the
     * connection's count of every byte queued or in flight.
     */
    CompressionPipeline(ExecutorService pool, int threads, QueuedBytes queuedBytes) {
        this(pool, threads, queuedBytes, IntakeWire.StreamWriter::new);
    }

    /** As above, with each socket's writer from {@code streams}. */
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
     * A socket opened: its messages are one zstd frame from here, written by a fresh {@link IntakeWire.StreamWriter}.
     * Drops what is pending first, then closes the last socket's writer.
     */
    void stream() {
        end();
        stream = streams.get();
    }

    /** Drops what is pending and closes the socket's writer, if any. */
    private void end() {
        drop();
        // A taken message's write may still be running if its taker was interrupted; zstd refuses to close mid-write.
        if (streamTail != null) streamTail.handle((message, failed) -> null).join();
        if (stream != null) stream.close();
        stream = null;
        streamTail = null;
        open = null;
    }

    /** Whether the socket's messages are one zstd frame, so none may go missing. */
    boolean streaming() {
        return stream != null;
    }

    /**
     * Starts writing {@code records}, {@code bytes} long in all, as the next part of the open message, which {@code end}
     * ends. The list may be reused after. A part the pool refuses fails its message as a write that throws does.
     */
    void submit(List<byte[]> records, int bytes, boolean end) {
        List<byte[]> own = List.copyOf(records);
        IntakeWire.StreamWriter writer = stream;
        AtomicLong counted = open == null ? new AtomicLong() : open;
        CompletableFuture<byte[]> after = streamTail == null ? CompletableFuture.completedFuture(null) : streamTail;
        writing.incrementAndGet();
        writingBytes.addAndGet(bytes);
        CompletableFuture<byte[]> part = after.<byte[]>handleAsync(
                        (previous, failed) -> {
                            if (failed != null) throw new CompletionException(failed);
                            return written(writer, own, bytes, end, counted);
                        },
                        pool)
                .whenComplete((message, failed) -> {
                    writing.decrementAndGet();
                    writingBytes.addAndGet(-bytes);
                });
        counted.addAndGet(bytes);
        open = counted;
        streamTail = part;
        if (!end) return;
        pending.add(new Pending(counted, part));
        open = null;
    }

    /**
     * A part written: the compressed bytes it adds to the message replace its records' in the queued count. The part
     * that ends the message returns it as its own array; any other returns null.
     */
    private byte[] written(
            IntakeWire.StreamWriter writer, List<byte[]> records, int bytes, boolean end, AtomicLong counted) {
        int held = writer.held();
        ByteBuffer written = writer.write(records, bytes, end);
        if (written == null) {
            long delta = writer.held() - held - bytes;
            counted.addAndGet(delta);
            queuedBytes.addAndGet(delta);
            return null;
        }
        byte[] out = new byte[written.remaining()];
        written.get(out);
        long delta = out.length - held - bytes;
        counted.addAndGet(delta);
        queuedBytes.addAndGet(delta);
        return out;
    }

    /** Whether a message is open: parts of it were submitted and none ended it. */
    boolean open() {
        return open != null;
    }

    /**
     * The oldest submitted message, once written; null when none is pending. Its bytes stay queued until {@link #sent}.
     * Interrupted, the message is given up and its bytes leave the count once its write ends.
     */
    byte[] take() throws InterruptedException, ExecutionException {
        try {
            return take(Long.MAX_VALUE);
        } catch (TimeoutException unbounded) {
            throw new AssertionError(unbounded);
        }
    }

    /** As {@link #take}, the message given up as on an interrupt once {@code timeoutNanos} pass before it is written. */
    byte[] take(long timeoutNanos) throws InterruptedException, ExecutionException, TimeoutException {
        Pending head = pending.poll();
        if (head == null) return null;
        try {
            return head.message().get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException failed) {
            queuedBytes.addAndGet(-head.counted().get());
            throw failed;
        } catch (InterruptedException | TimeoutException given) {
            head.message()
                    .whenComplete((message, failed) ->
                            queuedBytes.addAndGet(failed == null ? -message.length : -head.counted().get()));
            throw given;
        }
    }

    /** Whether nothing submitted is left: no message open and none pending. */
    boolean idle() {
        return open == null && pending.isEmpty();
    }

    /** A message from {@link #take} left on the socket or was given up. */
    void sent(byte[] message) {
        queuedBytes.addAndGet(-message.length);
    }

    /**
     * Drops every pending message and the open one, waiting out the writes already started so their bytes leave the
     * count. The stream cannot go on past a dropped message: the socket's writer must not write again.
     */
    void drop() {
        Pending head;
        while ((head = pending.poll()) != null) {
            try {
                queuedBytes.addAndGet(-head.message().join().length);
            } catch (RuntimeException failed) {
                queuedBytes.addAndGet(-head.counted().get());
            }
        }
        AtomicLong counted = open;
        if (counted == null) return;
        open = null;
        streamTail.handle((part, failed) -> null).join();
        queuedBytes.addAndGet(-counted.get());
    }

    /** Drops what is pending and frees the socket's zstd context; the shared pool keeps running. */
    @Override
    public void close() {
        end();
    }
}
