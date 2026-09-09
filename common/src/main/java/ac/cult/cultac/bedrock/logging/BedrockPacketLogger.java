package ac.cult.cultac.bedrock.logging;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

public final class BedrockPacketLogger implements AutoCloseable {
    private final PendingPacketCaptures pending = new PendingPacketCaptures();
    private final Map<Object, PacketLogCapture> captures = new ConcurrentHashMap<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final Path directory;
    private final String versions;
    private final Consumer<String> report;
    private final ScheduledExecutorService writer;
    private boolean closed;

    public BedrockPacketLogger(Path directory, String versions, Consumer<String> report) {
        this.directory = directory;
        this.versions = versions;
        this.report = report;
        writer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "cultac-geyser-packet-logger");
            thread.setDaemon(true);
            return thread;
        });
        writer.scheduleWithFixedDelay(this::drain, 0, 250, TimeUnit.MILLISECONDS);
    }

    public synchronized String arm(String username) {
        if (closed) return "Geyser packet logger is unavailable.";
        String evicted = pending.arm(username);
        return "Armed Geyser packet capture for " + username + " on next join."
                + (evicted == null ? "" : " Evicted pending username: " + evicted);
    }

    public synchronized void onInitialize(Object connection, String username, int protocol) {
        if (!closed && pending.consume(username)) {
            start(connection, username, protocol, null, "SessionInitializeEvent; initial login/negotiation excluded", report);
        }
    }

    public synchronized void toggle(Object connection, String username, int protocol, UUID uuid, Consumer<String> feedback) {
        if (closed) {
            feedback.accept("Geyser packet logger is unavailable.");
            return;
        }
        PacketLogCapture existing = captures.get(connection);
        if (existing != null) {
            existing.stop("manual stop");
            existing.finished.whenComplete((path, failure) -> feedback.accept(failure == null
                    ? "Saved Geyser packet log: " + path : "Geyser packet log failed: " + existing.path + ": " + failure.getMessage()));
            return;
        }
        start(connection, username, protocol, uuid, "online command", feedback);
    }

    private void start(Object connection, String username, int protocol, UUID uuid, String boundary, Consumer<String> feedback) {
        UUID id = UUID.randomUUID();
        Path path = directory.resolve(System.currentTimeMillis() + "-" + id + ".txt");
        String header = "log_version=1\ncapture_id=" + id + "\ntime=" + Instant.now()
                + "\n" + versions + "\nbedrock_username=" + BedrockPacketLogFormatter.escape(username)
                + "\nbedrock_protocol=" + protocol + "\njava_uuid=" + uuid + "\nboundary=" + boundary
                + "\noutbound=observed writes, not client acknowledgement\n\n";
        PacketLogCapture capture = new PacketLogCapture(path, header, queuedBytes, 16L << 20, 64L << 20);
        captures.put(connection, capture);
        capture.opened.whenComplete((file, failure) -> feedback.accept(failure == null
                ? "Started Geyser packet capture for " + username + ": " + file
                : "Unable to open Geyser packet log: " + path + ": " + failure.getMessage()));
        capture.finished.whenComplete((file, failure) -> report.accept(failure == null
                ? "Saved Geyser packet log: " + file : "Geyser packet log incomplete: " + path + ": " + failure.getMessage()));
    }

    public void record(Object connection, String direction, BedrockPacket packet) {
        PacketLogCapture capture = captures.get(connection);
        if (capture != null && capture.recording()) {
            capture.record(() -> BedrockPacketLogFormatter.format(direction, packet));
        }
    }

    public void identity(Object connection, UUID uuid) {
        PacketLogCapture capture = captures.get(connection);
        if (capture != null) capture.record(() -> "IDENTITY java_uuid=" + uuid);
    }

    public void stop(Object connection, String reason) {
        PacketLogCapture capture = captures.get(connection);
        if (capture != null) capture.stop(reason);
    }

    public void stopAll(String reason) {
        captures.values().forEach(capture -> capture.stop(reason));
    }

    private void drain() {
        captures.forEach((connection, capture) -> {
            capture.drain();
            if (capture.finished.isDone()) captures.remove(connection, capture);
        });
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending.clear();
            stopAll("shutdown");
        }
        writer.execute(() -> {
            while (!captures.isEmpty()) drain();
        });
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                report.accept("Geyser packet logger is still draining files after shutdown timeout.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
