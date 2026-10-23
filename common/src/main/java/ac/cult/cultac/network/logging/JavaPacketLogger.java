package ac.cult.cultac.network.logging;

import ac.cult.cultac.logging.PacketLogCapture;
import ac.cult.cultac.logging.PacketLogText;
import ac.cult.cultac.logging.PendingPacketCaptures;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.UnsupportedOnVersionException;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Records only owned text; the writer never reads player state or packet buffers. */
public final class JavaPacketLogger implements AutoCloseable {
    private record Capture(PacketLogCapture log, AtomicLong skipped) {}

    private final PendingPacketCaptures pending = new PendingPacketCaptures();
    private final Map<CultConnection, Capture> captures = new ConcurrentHashMap<>();
    /** Never below the number of captures: raised before each put, lowered after each successful remove. */
    private final AtomicInteger captureBound = new AtomicInteger();

    private final AtomicLong queuedBytes = new AtomicLong();
    private final Path directory;
    private final String platform, version;
    private final Consumer<String> report;
    private final ScheduledExecutorService writer;
    private boolean closed;

    public JavaPacketLogger(Path directory, String platform, String version, Consumer<String> report) {
        this.directory = directory;
        this.platform = platform;
        this.version = version;
        this.report = report;
        writer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "cultac-java-packet-logger");
            thread.setDaemon(true);
            return thread;
        });
        writer.scheduleWithFixedDelay(this::drain, 0, 250, TimeUnit.MILLISECONDS);
    }

    public PacketLogObserver observer(CultConnection connection) {
        return new PacketLogObserver() {
            @Override
            public boolean active() {
                if (captureBound.get() == 0) return false;
                var capture = captures.get(connection);
                return capture != null && capture.log.recording();
            }

            @Override
            public void packet(PacketDirection direction, ConnectionPhase phase, ByteBuf frame) {
                observe(connection, direction, phase, frame);
            }

            @Override
            public void identity(User.Profile profile) {
                authenticated(connection, profile);
            }

            @Override
            public void stop(String reason) {
                var capture = captures.get(connection);
                if (capture != null) capture.log.stop(reason);
            }
        };
    }

    public synchronized String arm(String username) {
        if (closed) return "Java packet logger is unavailable.";
        String evicted = pending.arm(username);
        return "Armed Java packet capture for " + username + " on next join (Java-side profile name)."
                + (evicted == null ? "" : " Evicted pending username: " + evicted);
    }

    /** Call on this exact connection's packet owner. */
    public synchronized void toggle(CultConnection connection, Consumer<String> feedback) {
        if (closed || connection.disconnected() || !connection.channel().isActive()) {
            feedback.accept("Java packet connection is unavailable.");
            return;
        }
        var existing = captures.get(connection);
        if (existing != null && existing.log.finished().isDone()) {
            remove(connection, existing);
            existing = null;
        }
        if (existing != null) {
            if (!existing.log.recording()) {
                feedback.accept("Java packet capture is stopping; wait for it to finish.");
                return;
            }
            existing.log.stop("manual stop");
            var stopping = existing;
            stopping.log
                    .finished()
                    .whenComplete((path, failure) -> feedback.accept(
                            failure == null
                                    ? "Saved Java packet log: " + path
                                    : "Java packet log incomplete: " + stopping.log.path() + ": "
                                            + failure.getMessage()));
            return;
        }
        var profile = connection.user() == null ? null : connection.user().getProfile();
        if (profile == null) {
            feedback.accept("Player has no authenticated Java packet connection.");
            return;
        }
        start(connection, profile.getName(), profile.getUUID(), "online command", feedback);
    }

    private synchronized void authenticated(CultConnection connection, User.Profile profile) {
        if (closed) return;
        if (!captures.containsKey(connection) && pending.consume(profile.getName())) {
            start(
                    connection,
                    profile.getName(),
                    profile.getUUID(),
                    "authenticated connection; configuration onward",
                    report);
        }
        var capture = captures.get(connection);
        if (capture != null)
            capture.log.record(() -> "IDENTITY username=" + PacketLogText.escape(profile.getName())
                    + " java_uuid=" + profile.getUUID() + " client_protocol=" + protocol(connection.getClientProtocol())
                    + " java_translation=" + (connection.bedrockBridge() != null));
    }

    private void start(
            CultConnection connection, String username, UUID uuid, String boundary, Consumer<String> feedback) {
        UUID id = UUID.randomUUID();
        Path path = directory.resolve(System.currentTimeMillis() + "-" + id + ".txt");
        String header = "log_version=1\ncapture_id=" + id + "\ntime=" + Instant.now()
                + "\ncultac_version=" + PacketLogText.escape(version) + "\nplatform=" + PacketLogText.escape(platform)
                + "\njava_username=" + PacketLogText.escape(username) + "\njava_uuid=" + uuid
                + "\nclient_protocol=" + protocol(connection.getClientProtocol())
                + "\nobserved_protocol=" + protocol(connection.getObservedProtocol())
                + "\nmodel_protocol="
                + protocol(connection.dispatcher().runtime().data().version())
                + "\njava_translation=" + (connection.bedrockBridge() != null)
                + "\nboundary=" + boundary
                + "\nscope=existing decoded fields; projected numeric values use model protocol; unread payloads omitted"
                + "\noutbound=observed writes, not client acknowledgement\n\n";
        AtomicLong skipped = new AtomicLong();
        PacketLogCapture log = new PacketLogCapture(
                path, header, queuedBytes, 16L << 20, 64L << 20, () -> " skipped_unreadable=" + skipped.get());
        captureBound.incrementAndGet();
        if (captures.put(connection, new Capture(log, skipped)) != null) captureBound.decrementAndGet();
        log.opened()
                .whenComplete((file, failure) -> feedback.accept(
                        failure == null
                                ? "Started Java packet capture for " + username + ": " + file
                                : "Unable to open Java packet log: " + path + ": " + failure.getMessage()));
        log.finished()
                .whenComplete((file, failure) -> report.accept(
                        failure == null
                                ? "Saved Java packet log: " + file
                                : "Java packet log incomplete: " + path + ": " + failure.getMessage()));
    }

    private void observe(CultConnection connection, PacketDirection direction, ConnectionPhase phase, ByteBuf frame) {
        if (captureBound.get() == 0) return;
        var capture = captures.get(connection);
        if (capture == null || !capture.log.recording()) return;
        try {
            int id = Wire.peekVarInt(frame);
            var ids = connection.runtime().data().packets(phase, direction);
            if (id < 0 || id >= ids.size()) {
                capture.skipped.incrementAndGet();
                return;
            }
            String name = ids.name(id);
            var values = connection
                    .packets()
                    .observe(
                            phase,
                            direction,
                            frame,
                            connection,
                            type -> !type.isOpaque() || type.codec().readsEntirePayload());
            for (var value : values) {
                capture.log.record(
                        () -> JavaPacketLogFormatter.format(direction, phase, id, name, value.type(), value.packet()));
            }
            if (values.isEmpty()) capture.skipped.incrementAndGet();
        } catch (UnsupportedOnVersionException unsupported) {
            capture.skipped.incrementAndGet();
        } catch (RuntimeException | LinkageError failure) {
            capture.log.stop(
                    "INCOMPLETE: packet decoding failed: " + failure.getClass().getSimpleName());
        }
    }

    private static String protocol(ProtocolVersion protocol) {
        return protocol == null ? "unknown" : protocol.protocol() + " (" + protocol.minecraftVersion() + ")";
    }

    private void drain() {
        captures.forEach((connection, capture) -> {
            capture.log.drain();
            if (capture.log.finished().isDone()) remove(connection, capture);
        });
    }

    private void remove(CultConnection connection, Capture capture) {
        if (captures.remove(connection, capture)) captureBound.decrementAndGet();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending.clear();
            captures.values().forEach(capture -> capture.log.stop("shutdown"));
        }
        writer.execute(() -> {
            while (!captures.isEmpty()) drain();
        });
        writer.shutdown();
        try {
            while (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                report.accept("Java packet logger is still draining files; runtime remains loaded.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Packet logger cleanup interrupted", interrupted);
        }
    }
}
