package ac.cult.cultac.bedrock.logging;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Producers snapshot records; only the logger's writer thread touches the file. */
final class PacketLogCapture {
    private record Record(byte[] bytes) { }
    private final ArrayDeque<Record> queue = new ArrayDeque<>();
    private final AtomicLong globalBytes;
    private final long captureLimit;
    private final long globalLimit;
    private final long started = System.nanoTime();
    private final String header;
    final Path path;
    final CompletableFuture<Path> opened = new CompletableFuture<>();
    final CompletableFuture<Path> finished = new CompletableFuture<>();
    private BufferedWriter writer;
    private long pendingBytes;
    private long sequence;
    private String stopReason;

    PacketLogCapture(Path path, String header, AtomicLong globalBytes, long captureLimit, long globalLimit) {
        this.path = path;
        this.header = header;
        this.globalBytes = globalBytes;
        this.captureLimit = captureLimit;
        this.globalLimit = globalLimit;
    }

    synchronized boolean recording() {
        return stopReason == null;
    }

    synchronized void record(Supplier<String> snapshot) {
        if (stopReason != null) return;
        try {
            String value = snapshot.get();
            byte[] bytes = ("seq=" + (sequence + 1) + " elapsed_ns=" + (System.nanoTime() - started)
                    + " " + value + "\n").getBytes(StandardCharsets.UTF_8);
            if (pendingBytes + bytes.length > captureLimit) {
                stop("INCOMPLETE: per-capture queue overflow");
                return;
            }
            long total = globalBytes.addAndGet(bytes.length);
            if (total > globalLimit) {
                globalBytes.addAndGet(-bytes.length);
                stop("INCOMPLETE: global queue overflow");
                return;
            }
            sequence++;
            pendingBytes += bytes.length;
            queue.add(new Record(bytes));
        } catch (RuntimeException | LinkageError failure) {
            stop("INCOMPLETE: packet formatting failed: " + failure.getClass().getSimpleName());
        }
    }

    synchronized void stop(String reason) {
        if (stopReason == null) stopReason = reason;
    }

    private synchronized Record poll() {
        Record record = queue.poll();
        if (record != null) {
            pendingBytes -= record.bytes.length;
            globalBytes.addAndGet(-record.bytes.length);
        }
        return record;
    }

    void drain() {
        if (finished.isDone()) return;
        try {
            if (writer == null) {
                Files.createDirectories(path.getParent());
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                writer.write(header);
                writer.flush();
                opened.complete(path);
            }
            // Bound each pass so one busy capture cannot starve the others.
            for (int i = 0; i < 1024; i++) {
                Record record = poll();
                if (record == null) break;
                writer.write(new String(record.bytes, StandardCharsets.UTF_8));
            }
            String reason;
            synchronized (this) {
                reason = queue.isEmpty() ? stopReason : null;
            }
            if (reason != null) {
                writer.write("END records=" + sequence + " reason=" + BedrockPacketLogFormatter.escape(reason) + "\n");
                writer.close();
                if (reason.startsWith("INCOMPLETE:")) {
                    finished.completeExceptionally(new IOException(reason));
                } else {
                    finished.complete(path);
                }
            } else {
                writer.flush();
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) {
                stop("INCOMPLETE: write failure");
                queue.clear();
                globalBytes.addAndGet(-pendingBytes);
                pendingBytes = 0;
            }
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            opened.completeExceptionally(failure);
            finished.completeExceptionally(failure);
        }
    }
}
