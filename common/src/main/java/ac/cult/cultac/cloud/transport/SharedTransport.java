package ac.cult.cultac.cloud.transport;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What every socket on one service shares: one {@link HttpClient} (one selector thread, its callbacks on virtual
 * threads), one compression pool, and the count of bytes queued across every socket in the process.
 *
 * <p>Each connection {@link #acquire acquires} it when created and {@link #release releases} it when its sender has shut
 * down; once {@link #retire retired} and released by the last of them, the client and the pool stop. Nothing here blocks.
 */
final class SharedTransport {
    private static final AtomicInteger NUMBER = new AtomicInteger();
    /** Bytes queued across every socket in the process; past it records are refused. */
    static final long MAX_PROCESS_QUEUED_BYTES = 128L * 1024 * 1024;

    static final AtomicLong PROCESS_QUEUED = new AtomicLong();

    final HttpClient http;
    final ExecutorService pool;
    final int threads;
    private final ExecutorService callbacks;
    private int users;
    private boolean retired;
    private boolean stopped;

    SharedTransport() {
        threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 2, 4);
        int number = NUMBER.incrementAndGet();
        callbacks = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cult-cloud-http-" + number + "-", 0).factory());
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(callbacks)
                .build();
        pool = CompressionPipeline.newPool(threads, "cult-cloud-compress-" + number);
    }

    synchronized void acquire() {
        users++;
    }

    synchronized void release() {
        users--;
        stopIfDone();
    }

    /** No connection acquires it again; it stops once the last one releases it. */
    synchronized void retire() {
        retired = true;
        stopIfDone();
    }

    private void stopIfDone() {
        if (!retired || users > 0 || stopped) return;
        stopped = true;
        pool.shutdown();
        http.shutdownNow();
        callbacks.shutdown();
    }
}
