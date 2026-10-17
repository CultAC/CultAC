package ac.cult.cultac.cloud.transport;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.capture.PlayerText;
import ac.cult.cultac.cloud.diag.DiagUpload;
import ac.cult.cultac.cloud.diag.DiagWire;
import ac.cult.cultac.cloud.diag.Diagnostics;
import ac.cult.cultac.cloud.diag.Limits;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.common.PropertiesUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * One server id's socket to the intake service. {@code POST /api/v1/connect} through the load balancer hands out a
 * replica's intake socket, which is then opened directly and kept, reconnecting with backoff when it drops.
 *
 * <p>Streams queue records from any thread; one virtual sender thread cuts them into messages of up to the size connect
 * allows once the service resolves their probe markers, and at most {@link #MAX_WRITE_BYTES} record bytes, which a
 * {@link CompressionPipeline} compresses on the pool every socket shares, and the sender writes out in the order cut,
 * so they reach the service in order. A new socket starts a new generation:
 * records queued for the old one are dropped and every stream is told to join again, since the service knows nothing
 * of players joined on the old one.
 *
 * <p>The queue is one FIFO for every player, so a backlog delays everyone alike: a small frame queued behind a section
 * burst waits for the bytes ahead of it to drain, at most {@link #MAX_QUEUED_BYTES} at the pipeline's rate. Each frame
 * is one player's tick, bounded by {@link #maxFrameBytes()}, so one burst holds the queue for one frame at a time.
 */
public final class CloudConnection {
    /** A player's side of the socket. Called from the connection's threads. */
    public interface Stream {
        /** A socket of this generation is open: join it. */
        void connected(long generation);

        /** The socket of this generation is gone. */
        void disconnected(long generation);

        void reply(long generation, IntakeWire.Reply reply);
    }

    /** What a connection tells the registry that made it. */
    interface Owner {
        /** No stream has used the connection for its idle period. Called on the sender thread. */
        void idle(CloudConnection connection);

        /**
         * The service and this connection share no protocol, so connecting again cannot help. Called on the sender
         * thread.
         */
        void unavailable(CloudConnection connection, String why);

        /** The service sent a finding. Called on the socket's listener thread. */
        void finding(IntakeWire.Event event);
    }

    private static final Gson GSON = new Gson();
    /** Numbers connections for their thread names. */
    private static final AtomicInteger NUMBER = new AtomicInteger();
    /** Records queued plus messages being written or sent, per socket; past it records are refused. */
    private static final long MAX_QUEUED_BYTES = 64L * 1024 * 1024;
    /** The most record bytes compressed as one message, so a backlog spreads over the pipeline's threads. */
    static final int MAX_WRITE_BYTES = 1024 * 1024;

    private static final long REFUSED_LOG_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** A connection no stream has used this long closes, freeing its zstd context and the service's slot. */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);
    /** HTTP status of a connect whose offer the service cannot take. */
    static final int UNPROCESSABLE = 422;
    /** The intake's close code for a socket another connection with the same server id replaced. */
    static final int TAKEN_OVER = 4000;
    /** The most bytes of connect's answer read; past it the connect fails. */
    static final int MAX_CONNECT_BODY_BYTES = 64 * 1024;
    /** The smallest message limit connect may answer; below it the connect fails. */
    static final int MIN_MESSAGE_BYTES = 64 * 1024;
    /** The largest message limit used, whatever connect answers. */
    static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;

    private static final long FLUSH_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    /** How long one message's write may take before the socket is given up. */
    private static final long WRITE_NANOS = TimeUnit.SECONDS.toNanos(15);
    /** How long a retiring socket may take to send what is queued before it closes regardless. */
    private static final long RETIRE_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** How long a socket another connection took its server id from waits before connecting again. */
    private static final long TAKEOVER_NANOS = TimeUnit.SECONDS.toNanos(60);

    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    /** A socket must stay open this long before a reconnect skips the backoff. */
    private static final long STABLE_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** A socket quiet this long is pinged; quiet as long again after the ping, it is given up. */
    private static final long QUIET_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** Diagnostic parts wait while more than this many record bytes, two messages' worth, are queued for the socket. */
    static final long DIAG_LOW_WATERMARK = 2L * MAX_WRITE_BYTES;
    /** The most bytes one reply message may gather across its fragments; past it the socket is dropped. */
    static final int MAX_REPLY_BYTES = 16 * 1024 * 1024;

    private static final LogThrottle REPLY_FAILURES = new LogThrottle();
    private static final AtomicBoolean ZSTD_WARNED = new AtomicBoolean();
    private static final AtomicBoolean RADIUS_WARNED = new AtomicBoolean();
    /** The config files that decide what CultAC does, hashed for the service's record of each session. */
    private static final List<String> CONFIG_FILES = List.of("config.yml", "cult.yml", "punishments.yml");

    private final URI connect;
    private final String apiKey;
    private final String serverId;
    private final SharedTransport shared;
    private final Owner owner;
    private final HttpClient http;
    private final Map<UUID, Stream> streams = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Queued> queue = new ConcurrentLinkedQueue<>();
    private final QueuedBytes queuedBytes;
    private final AtomicLong refused = new AtomicLong();
    /** EVENT replies: findings the service sends. */
    private final AtomicLong events = new AtomicLong();

    private final List<Integer> protocols;
    private final CompressionPipeline pipeline;
    private final Thread sender;
    private final DiagUpload diagUpload = new DiagUpload();
    private final Diagnostics diagnostics;

    private volatile WebSocket socket;
    private volatile long generation;
    private volatile int maxMessageBytes;
    /** The open socket's generation, the terms it was opened on, and the {@code textStripped} its connect said. */
    private volatile Agreed agreed = new Agreed(0, Terms.PROTOCOL_3, true);

    /** The agent this socket announced; a reload that changes its mode or config hash reconnects. */
    private volatile JsonObject announced;
    /** On reload, the sender compares the agent with the one announced. */
    private volatile boolean reloaded;
    /** When a stream last registered or unregistered, or the registry last handed this connection out. */
    private volatile long usedNanos;

    private final long idleAfterNanos;

    private volatile boolean closed;
    /** Set by {@link #retire}: the socket sends what is queued, closes normally and never reconnects. */
    private volatile boolean retiring;
    /** Set by {@link #freeze}: no socket opens again; an open one stays to send what is queued. */
    private volatile boolean frozen;
    /** The service closed the socket for another with this server id: the next connect waits {@link #takeoverNanos}. */
    private volatile boolean takenOver;

    private final long takeoverNanos;

    private volatile long retireByNanos;
    private volatile long openedNanos;
    /** How long the last socket stayed open, set when it is lost; -1 once the sender has waited for it. */
    private volatile long lastLifeNanos = -1;

    private final Backoff backoff = new Backoff(MIN_BACKOFF, MAX_BACKOFF, STABLE_NANOS);
    private final SocketLiveness liveness;
    private final LongSupplier clock;
    private boolean failing;
    private long refusedLogged;
    private long refusedLogNanos = System.nanoTime();

    /** A queued record; a frame's {@code resolvedExtra} is what its probe markers add once the service resolves them. */
    record Queued(long generation, byte[] record, long resolvedExtra) {
        int length() {
            return record.length;
        }

        /** The bytes the service counts for this entry against a message's limit. */
        long resolvedLength() {
            return length() + resolvedExtra;
        }
    }

    /**
     * What the service agreed to at connect: the intake protocol and, on protocol 5, the columns (Chebyshev) and the
     * sections above and below the player's within which its simulation world's sections stream.
     */
    public record Terms(int protocol, int chunkRadius, int sectionRadius) {
        public static final Terms PROTOCOL_3 = new Terms(IntakeWire.PROTOCOL_3, 0, 0);
        /** Protocol 5's radii when connect's answer leaves them out. */
        public static final int DEFAULT_CHUNK_RADIUS = 2;

        public static final int DEFAULT_SECTION_RADIUS = 2;
        /** The most either protocol 5 radius may be: past it a player's event loop would scan and send too much. */
        public static final int MAX_RADIUS = 8;

        /** Protocol 5's terms: sections within {@code chunkRadius} columns and {@code sectionRadius} sections. */
        public static Terms sections(int chunkRadius, int sectionRadius) {
            return new Terms(IntakeWire.PROTOCOL_5, chunkRadius, sectionRadius);
        }

        /** Terrain goes out as sections of the simulation world, and chunk packets never do. */
        public boolean sections() {
            return protocol == IntakeWire.PROTOCOL_5;
        }
    }

    private record Agreed(long generation, Terms terms, boolean textStripped) {}

    /** The quiet and idle periods, the clock liveness, idleness and retirement read, and the wait after a takeover. */
    record Timing(long quietNanos, long idleNanos, LongSupplier clock, long takeoverNanos) {
        static final Timing DEFAULT = new Timing(QUIET_NANOS, IDLE_NANOS, System::nanoTime);

        Timing(long quietNanos, long idleNanos, LongSupplier clock) {
            this(quietNanos, idleNanos, clock, TAKEOVER_NANOS);
        }
    }

    /**
     * The wait before the next connect: doubling from {@code min} to {@code max} while connects fail or sockets close
     * within {@code stableNanos} of opening, none after a socket that stayed open that long.
     */
    static final class Backoff {
        private final Duration min;
        private final Duration max;
        private final long stableNanos;
        private Duration next;

        Backoff(Duration min, Duration max, long stableNanos) {
            this.min = min;
            this.max = max;
            this.stableNanos = stableNanos;
            next = min;
        }

        /** A connect failed: the wait before the next one. */
        Duration failed() {
            Duration wait = next;
            Duration doubled = next.multipliedBy(2);
            next = doubled.compareTo(max) > 0 ? max : doubled;
            return wait;
        }

        /** A socket closed after {@code lifeNanos} open: the wait before reconnecting. */
        Duration closed(long lifeNanos) {
            if (lifeNanos < stableNanos) return failed();
            next = min;
            return Duration.ZERO;
        }
    }

    /** A connection on {@code shared}'s client and pool, reporting to {@code owner}. */
    CloudConnection(
            URI connect,
            String apiKey,
            String serverId,
            List<Integer> protocols,
            SharedTransport shared,
            Owner owner,
            Timing timing) {
        this.connect = connect;
        this.apiKey = apiKey;
        this.serverId = serverId;
        this.protocols = List.copyOf(protocols);
        this.shared = shared;
        this.owner = owner;
        liveness = new SocketLiveness(timing.quietNanos());
        clock = timing.clock();
        idleAfterNanos = timing.idleNanos();
        takeoverNanos = timing.takeoverNanos();
        usedNanos = clock.getAsLong();
        http = shared.http;
        queuedBytes = new QueuedBytes(SharedTransport.PROCESS_QUEUED);
        shared.acquire();
        int number = NUMBER.incrementAndGet();
        pipeline = new CompressionPipeline(shared.pool, shared.threads, queuedBytes);
        diagnostics = new Diagnostics(diagUpload, serverId);
        sender = Thread.ofVirtual().name("cult-cloud-" + number).unstarted(this::run);
        sender.start();
    }

    long lastHeardNanos() {
        return liveness.heardNanos();
    }

    /** The server id this socket announces. */
    String serverId() {
        return serverId;
    }

    /**
     * On config reload, the sender reconnects when its mode or config hash differs from the announced one. The config
     * files are read on the sender, so this does not block.
     */
    void reloaded() {
        reloaded = true;
        LockSupport.unpark(sender);
    }

    /** The registry handed this connection out: its idle period starts again. */
    void touch() {
        usedNanos = clock.getAsLong();
    }

    /** Whether no stream is registered and none has come or gone for the idle period. */
    boolean idle() {
        return streams.isEmpty() && clock.getAsLong() - usedNanos >= idleAfterNanos;
    }

    boolean closed() {
        return closed;
    }

    /** Whether the socket's messages are one zstd frame, whose native context is held until the next socket. */
    boolean streaming() {
        return pipeline.streaming();
    }

    /** Stops this connection. Its sender thread closes the socket and the pipeline, so this does not block. */
    void close() {
        closed = true;
        diagnostics.close();
        WebSocket open = socket;
        if (open != null) lost(open, null);
        sender.interrupt();
    }

    /**
     * Stops this connection once what is queued has gone out, within {@link #RETIRE_NANOS}: its socket then closes
     * with a normal close, and it never reconnects. Without a socket it stops at once. Does not block.
     */
    void retire() {
        retireByNanos = clock.getAsLong() + RETIRE_NANOS;
        retiring = true;
        if (socket == null) {
            close();
            return;
        }
        LockSupport.unpark(sender);
    }

    /**
     * A newer runtime is taking over: no socket opens again, a connect in flight is given up, and an open socket stays
     * to send what is still queued. Does not block.
     */
    void freeze() {
        frozen = true;
        LockSupport.unpark(sender);
    }

    /** Whether a retiring socket's time to send what is queued is up. */
    private boolean overdue() {
        return retiring && clock.getAsLong() - retireByNanos > 0;
    }

    /** One diagnostics line: counts only, never whom. */
    String diagnosticsSummary() {
        return diagnostics.status();
    }

    /** One line: what is queued, being compressed and refused. */
    String summary() {
        String line = String.format(
                Locale.ROOT,
                "Cloud %s: %s, queued %.1f of %d MiB, compressing %d (%.1f MiB) on %d threads, refused %d records",
                serverId,
                socket != null ? "connected" : "disconnected",
                queuedBytes.get() / 1048576.0,
                MAX_QUEUED_BYTES / 1048576,
                pipeline.writing(),
                pipeline.writingBytes() / 1048576.0,
                pipeline.threads(),
                refused.get());
        long findings = events.get();
        if (findings > 0) line += String.format(Locale.ROOT, ", %d findings received", findings);
        Terms terms = agreed.terms();
        if (terms.sections()) {
            return line
                    + String.format(
                            Locale.ROOT,
                            "; protocol 5, chunk radius %d, section radius %d",
                            terms.chunkRadius(),
                            terms.sectionRadius());
        }
        return line;
    }

    /** Joins {@code stream} now if the socket is open, and on every later connect. */
    public void register(UUID player, Stream stream) {
        streams.put(player, stream);
        touch();
        WebSocket open = socket;
        if (open != null) stream.connected(generation);
    }

    public void unregister(UUID player, Stream stream) {
        streams.remove(player, stream);
        touch();
    }

    /** The largest frame record the socket takes, headroom included; 0 until connected. */
    public int maxFrameBytes() {
        return Math.max(0, messageLimit() - 64);
    }

    /** The most bytes one message carries, probe markers resolved: the decompressed cap, less a flags byte and headroom. */
    private int messageLimit() {
        int max = maxMessageBytes;
        return max - 1 - max / 1024 - 64;
    }

    /** The terms the socket of {@code generation} was opened on; protocol 3's once it is gone. */
    public Terms terms(long generation) {
        Agreed current = agreed;
        return current.generation() == generation ? current.terms() : Terms.PROTOCOL_3;
    }

    /**
     * Whether the connect of the socket of {@code generation} said {@code textStripped}; true once it is gone. When it
     * said false, the intake strips text itself.
     */
    public boolean textStripped(long generation) {
        Agreed current = agreed;
        return current.generation() != generation || current.textStripped();
    }

    /**
     * Whether records are piling up: this socket's queue is over half its cap, or every socket's together over half the
     * process's.
     */
    public boolean backlogged() {
        return queuedBytes.get() > MAX_QUEUED_BYTES / 2
                || queuedBytes.process() > SharedTransport.MAX_PROCESS_QUEUED_BYTES / 2;
    }

    /** This socket's diagnostics: the watches the service asked for. */
    public Diagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * Queues a record for the socket of {@code generation}, one that grows by {@code resolvedExtra} once the service
     * resolves it. False when that socket is gone or too much is queued, and the record was dropped.
     */
    public boolean send(long generation, byte[] record, long resolvedExtra) {
        if (closed || generation != this.generation || socket == null) return false;
        if (!queuedBytes.tryAdd(record.length, MAX_QUEUED_BYTES, SharedTransport.MAX_PROCESS_QUEUED_BYTES)) {
            refused.incrementAndGet();
            return false;
        }
        queue.add(new Queued(generation, record, resolvedExtra));
        if (closed) dropQueued();
        return true;
    }

    private void run() {
        try {
            while (!closed) {
                try {
                    if (idle()) {
                        owner.idle(this);
                        if (closed) break;
                    }
                    if (socket == null) {
                        if (retiring || frozen) {
                            closed = true;
                            break;
                        }
                        long life = lastLifeNanos;
                        if (takenOver) {
                            takenOver = false;
                            lastLifeNanos = -1;
                            await(Duration.ofNanos(takeoverNanos));
                        } else if (life >= 0) {
                            lastLifeNanos = -1;
                            await(backoff.closed(life));
                        }
                        if (!closed && !retiring && !frozen) open();
                    } else {
                        checkAgent();
                        checkText();
                        pumpDiagnostics();
                        flush();
                        logRefused();
                        if (retiring && (queue.isEmpty() || overdue())) {
                            closeNormally();
                            break;
                        }
                        checkAlive();
                        LockSupport.parkNanos(FLUSH_NANOS);
                    }
                } catch (Throwable failure) {
                    if (closed) break;
                    if (retiring) {
                        closed = true;
                        break;
                    }
                    LogUtil.warn("Cloud connection failed: " + failure);
                    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                }
            }
        } finally {
            shutDown();
        }
    }

    /** On the sender thread of a retiring connection: the socket closes normally once its queue is sent. */
    private void closeNormally() {
        closed = true;
        diagnostics.close();
        WebSocket open = socket;
        long left = retireByNanos - clock.getAsLong();
        if (open == null || left <= 0) return;
        try {
            open.sendClose(WebSocket.NORMAL_CLOSURE, "superseded").get(left, TimeUnit.NANOSECONDS);
        } catch (Exception unsent) {
        }
    }

    /** On the sender thread once closed: the socket and the zstd context go away, and the shared transport is let go. */
    private void shutDown() {
        WebSocket open = socket;
        if (open != null) lost(open, null);
        dropQueued();
        try {
            pipeline.close();
        } catch (RuntimeException ignored) {
        }
        shared.release();
    }

    /** After a reload: drops the socket so the next connect announces the agent, when it differs from the one sent. */
    private void checkAgent() {
        if (!reloaded) return;
        reloaded = false;
        WebSocket open = socket;
        JsonObject sent = announced;
        if (open != null && sent != null && !agent().equals(sent)) lost(open, "agent changed on reload");
    }

    /** A codec that failed after connect said {@code textStripped} true: reconnect once to say false. */
    private void checkText() {
        WebSocket open = socket;
        if (open != null && agreed.textStripped() && !PlayerText.strips()) {
            lost(open, "a player text codec did not load; reconnecting with textStripped false");
        }
    }

    private void open() {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("serverId", serverId);
            String zstdUnavailable = IntakeWire.zstdUnavailable();
            List<Integer> offered = protocols(this.protocols, zstdUnavailable);
            if (offered.isEmpty()) {
                if (!closed) {
                    owner.unavailable(this, "intake protocol 5 needs zstd, which did not load: " + zstdUnavailable);
                }
                return;
            }
            if (zstdUnavailable != null && !ZSTD_WARNED.getAndSet(true)) {
                LogUtil.warn("Cloud intake protocol 5 needs zstd, which did not load; offering protocol 3 only: "
                        + zstdUnavailable);
            }
            JsonArray protocols = new JsonArray();
            for (int protocol : offered) protocols.add(protocol);
            body.add("protocols", protocols);
            boolean textStripped = PlayerText.stripsEvery();
            body.addProperty("textStripped", textStripped);
            boolean diag = zstdUnavailable == null;
            if (diag) body.addProperty("diag", DiagWire.VERSION);
            JsonObject agent = agent();
            body.add("agent", agent);
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(connect)
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                            .build(),
                    info -> new CappedBody(MAX_CONNECT_BODY_BYTES));
            if (closed || frozen) return;
            if (response.statusCode() == UNPROCESSABLE) {
                owner.unavailable(
                        this, "connect took none of protocols " + offered + ": " + LogThrottle.clip(response.body()));
                return;
            }
            if (response.statusCode() != 200) {
                retry("connect answered " + response.statusCode() + ": " + response.body());
                return;
            }
            Connected connected = GSON.fromJson(response.body(), Connected.class);
            if (connected == null || connected.url == null || !offered.contains(connected.protocol)) {
                retry("connect answered " + response.body());
                return;
            }
            if (!answerAllowed(connect, connected.url)) {
                retry("connect answered a socket URL that may not carry the key: " + loggable(connected.url));
                return;
            }
            if (connected.maxMessageBytes < MIN_MESSAGE_BYTES) {
                retry("connect answered a message limit of " + connected.maxMessageBytes + " bytes");
                return;
            }
            Terms terms = connected.terms();
            if (connected.radiusClamped() && !RADIUS_WARNED.getAndSet(true)) {
                LogUtil.warn("Cloud connect answered chunk radius " + connected.chunkRadius + " and section radius "
                        + connected.sectionRadius + "; using at most " + Terms.MAX_RADIUS);
            }
            maxMessageBytes = Math.min(connected.maxMessageBytes, MAX_MESSAGE_BYTES);
            long opened = generation + 1;
            WebSocket webSocket = handshake(http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .buildAsync(URI.create(connected.url), new Replies(opened)));
            if (webSocket == null) return;
            dropQueued();
            diagnostics.connected(opened, diag ? connected.diagLimits() : null);
            pipeline.stream(terms.sections());
            agreed = new Agreed(opened, terms, textStripped);
            generation = opened;
            announced = agent;
            reloaded = false;
            openedNanos = System.nanoTime();
            liveness.opened(clock.getAsLong());
            socket = webSocket;
            webSocket.request(1);
            failing = false;
            LogUtil.info("Cloud connected to " + loggable(connected.url) + " on intake protocol " + terms.protocol()
                    + (terms.sections()
                            ? " (chunk radius " + terms.chunkRadius() + ", section radius " + terms.sectionRadius()
                                    + ")"
                            : ""));
            for (Stream stream : streams.values()) {
                try {
                    stream.connected(opened);
                } catch (RuntimeException closing) {
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            retry(String.valueOf(failure));
        }
    }

    /**
     * Waits for a socket's handshake: the socket, or null when the connection stopped or froze meanwhile and the socket
     * was aborted. A handshake given up on, by a timeout or an interrupt, aborts its socket whenever that opens, so no
     * socket of a stopped connection outlives it.
     */
    private WebSocket handshake(CompletableFuture<WebSocket> connecting) throws Exception {
        AtomicBoolean claimed = new AtomicBoolean();
        connecting.thenAccept(late -> {
            if ((closed || frozen) && claimed.compareAndSet(false, true)) late.abort();
        });
        WebSocket webSocket;
        try {
            webSocket = connecting.get(15, TimeUnit.SECONDS);
        } catch (Exception givenUp) {
            connecting.thenAccept(late -> {
                if (claimed.compareAndSet(false, true)) late.abort();
            });
            throw givenUp;
        }
        if (!claimed.compareAndSet(false, true)) return null;
        if (closed || frozen) {
            webSocket.abort();
            return null;
        }
        return webSocket;
    }

    /**
     * The protocols of {@code configured} to offer: protocol 5 compresses with zstd, so not when it is unavailable. Empty
     * when none is left, and the upload stays off.
     */
    static List<Integer> protocols(List<Integer> configured, String zstdUnavailable) {
        if (zstdUnavailable == null) return configured;
        return configured.stream()
                .filter(protocol -> protocol == IntakeWire.PROTOCOL_3)
                .toList();
    }

    /**
     * Whether the socket URL connect answered may be sent the key: {@code wss}, or {@code ws} when the connect itself
     * went over {@code http} to a loopback host, with a host and no user info.
     */
    static boolean answerAllowed(URI connect, String answer) {
        URI socket;
        try {
            socket = new URI(answer);
        } catch (URISyntaxException | RuntimeException malformed) {
            return false;
        }
        if (socket.getScheme() == null || socket.getHost() == null || socket.getRawUserInfo() != null) return false;
        return switch (socket.getScheme().toLowerCase(Locale.ROOT)) {
            case "wss" -> true;
            case "ws" -> "http".equalsIgnoreCase(connect.getScheme()) && CloudSettings.loopback(connect.getHost());
            default -> false;
        };
    }

    /** {@code url} as {@code scheme://host:port/path}, clipped, for a log line: never its user info or query. */
    static String loggable(String url) {
        try {
            return loggable(new URI(url));
        } catch (URISyntaxException | RuntimeException malformed) {
            return "an unreadable URL";
        }
    }

    static String loggable(URI uri) {
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return LogThrottle.clip(uri.getScheme() + "://" + uri.getHost() + port + path);
    }

    /** Reads a response body as UTF-8, failing once it passes {@code limit} bytes. */
    static final class CappedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ReplyAssembler body;
        private final int limit;
        private Flow.Subscription subscription;

        CappedBody(int limit) {
            this.body = new ReplyAssembler(limit);
            this.limit = limit;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (!body.append(buffer)) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("answer over " + limit + " bytes"));
                    return;
                }
            }
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(new String(body.finish(), StandardCharsets.UTF_8));
        }
    }

    /** Connect's JSON answer. */
    static final class Connected {
        String url;
        int protocol;
        int maxMessageBytes;
        Integer chunkRadius;
        /** Protocol 5's sections above and below the player's. */
        Integer sectionRadius;
        /** Present when the service takes the diagnostics offered: its limits. */
        DiagLimitsJson diag;

        /** The diagnostics limits answered, clamped; null when the service took none. */
        Limits diagLimits() {
            DiagLimitsJson limits = diag;
            if (limits == null) return null;
            return Limits.of(
                    limits.enabled,
                    limits.ringTicks,
                    limits.playerRingBytes,
                    limits.serverRingBytes,
                    limits.maxPlayers,
                    limits.partBytes,
                    limits.uploadBytesPerSecond,
                    limits.bundleBytes,
                    limits.bundlesPerHour);
        }

        /** A protocol 5 radius: {@code fallback} when absent, within 0 and {@link Terms#MAX_RADIUS}. */
        private static int radius(Integer answered, int fallback) {
            return answered == null ? fallback : Math.clamp(answered, 0, Terms.MAX_RADIUS);
        }

        /** Whether protocol 5's answer named a radius past {@link Terms#MAX_RADIUS}. */
        boolean radiusClamped() {
            return protocol == IntakeWire.PROTOCOL_5
                    && (chunkRadius != null && chunkRadius > Terms.MAX_RADIUS
                            || sectionRadius != null && sectionRadius > Terms.MAX_RADIUS);
        }

        /** The terms of the protocol agreed; protocol 5's radii default when absent. */
        Terms terms() {
            if (protocol == IntakeWire.PROTOCOL_5) {
                return Terms.sections(
                        radius(chunkRadius, Terms.DEFAULT_CHUNK_RADIUS),
                        radius(sectionRadius, Terms.DEFAULT_SECTION_RADIUS));
            }
            return Terms.PROTOCOL_3;
        }
    }

    /** {@code ConnectResponse.diag} in connect's JSON. */
    static final class DiagLimitsJson {
        boolean enabled;
        long ringTicks;
        long playerRingBytes;
        long serverRingBytes;
        long maxPlayers;
        long partBytes;
        long uploadBytesPerSecond;
        long bundleBytes;
        long bundlesPerHour;
    }

    /** Which anticheat connects, from which build, with which config, and whether it acts on what it finds. */
    static JsonObject agent() {
        Properties build = PropertiesUtil.readProperties(CloudConnection.class, "cultac.properties");
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "cultac");
        agent.addProperty("version", buildProperty(build, "build.version"));
        agent.addProperty("commit", buildProperty(build, "git.commit"));
        agent.addProperty("configHash", configHash());
        agent.addProperty("mode", "enforcing");
        return agent;
    }

    /** A property the build filled in; null when it was left as its placeholder. */
    private static String buildProperty(Properties build, String key) {
        String value = build.getProperty(key);
        return value == null || value.isBlank() || value.startsWith("${") ? null : value;
    }

    private static String configHash() {
        try {
            return configHash(
                    CultAPI.INSTANCE.getGrimPlugin().getDataFolder(),
                    System.getProperty("user.language", "en").toLowerCase(Locale.ROOT));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * SHA-256 over the config files, each behind its name and length: the file on disk, or for a missing file the
     * bundled default in {@code language} (else English) that it falls back to; null when one cannot be read.
     */
    static String configHash(File folder, String language) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String name : CONFIG_FILES) {
                File file = new File(folder, name);
                byte[] content = file.isFile() ? Files.readAllBytes(file.toPath()) : bundledDefault(name, language);
                digest.update((name + '\n' + content.length + '\n').getBytes(StandardCharsets.UTF_8));
                digest.update(content);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unreadable) {
            return null;
        }
    }

    private static byte[] bundledDefault(String name, String language) throws IOException {
        String directory = name.equals("cult.yml") ? "/cult/" : "/" + name.substring(0, name.length() - 4) + "/";
        String path = directory + language + ".yml";
        if (CultAPI.class.getResource(path) == null) path = directory + "en.yml";
        try (InputStream in = CultAPI.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("no bundled default for " + name);
            return in.readAllBytes();
        }
    }

    /** Waits out the backoff; warns on the first failure in a row, not on every retry. */
    private void retry(String why) {
        if (!failing) {
            LogUtil.warn(
                    "Cloud connection to " + loggable(connect) + " failed: " + LogThrottle.clip(why) + "; retrying");
        }
        failing = true;
        await(backoff.failed());
    }

    /** Parks the sender for {@code wait}, or until the connection closes or retires. */
    private void await(Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        long left;
        while (!closed && !retiring && !frozen && (left = end - System.nanoTime()) > 0) LockSupport.parkNanos(left);
    }

    /**
     * Sends everything queued for the current socket, in messages {@link #nextMessage} cuts, compressed on the pipeline
     * while earlier ones are sent. What is dropped is dropped before it is compressed; on protocol 5 a compressed message
     * that cannot be sent closes the socket.
     */
    private void flush() throws Exception {
        WebSocket open = socket;
        long current = generation;
        int resolvedLimit = messageLimit();
        int limit = Math.min(resolvedLimit, MAX_WRITE_BYTES);
        List<byte[]> message = new ArrayList<>();
        int bytes = 0;
        try {
            while (true) {
                while (pipeline.hasRoom()
                        && !overdue()
                        && (bytes = nextMessage(queue, queuedBytes, current, limit, resolvedLimit, message)) > 0) {
                    pipeline.submit(message, bytes);
                    message.clear();
                    bytes = 0;
                }
                if (overdue()) return;
                byte[] next = pipeline.take();
                if (next == null) return;
                write(open, next);
                logRefused();
            }
        } catch (Exception failure) {
            queuedBytes.addAndGet(-bytes);
            pipeline.drop();
            // The service cannot decompress a stream past a message it never got.
            if (pipeline.streaming()) lost(open, "a message was not sent: " + failure);
            throw failure;
        }
    }

    /**
     * Takes the next message for the socket of {@code generation} off {@code queue} into {@code message}: whole entries
     * in order, at most {@code limit} record bytes and {@code resolvedLimit} bytes once the service resolves their probe
     * markers, or a single entry past either. Entries of other sockets are dropped. Returns the
     * message's record bytes, 0 when nothing is queued for the socket.
     */
    static int nextMessage(
            Queue<Queued> queue,
            QueuedBytes queuedBytes,
            long generation,
            int limit,
            long resolvedLimit,
            List<byte[]> message) {
        int bytes = 0;
        long resolved = 0;
        Queued queued;
        while ((queued = queue.peek()) != null) {
            int length = queued.length();
            if (queued.generation() != generation) {
                queue.poll();
                queuedBytes.addAndGet(-length);
                continue;
            }
            if (bytes > 0 && (bytes + length > limit || resolved + queued.resolvedLength() > resolvedLimit)) break;
            queue.poll();
            message.add(queued.record());
            bytes += length;
            resolved += queued.resolvedLength();
        }
        return bytes;
    }

    /**
     * Moves diagnostic parts onto the socket's queue while it is nearly drained and the upload bucket allows, so they
     * never hold up a frame. A part the queue refuses is lost; the service seals the bundle without it.
     */
    private void pumpDiagnostics() {
        if (!diagUpload.pending()) return;
        long current = generation;
        while (queuedBytes.get() <= DIAG_LOW_WATERMARK) {
            byte[] part = diagUpload.poll(current, System.nanoTime());
            if (part == null || !send(current, part, 0)) return;
        }
    }

    /**
     * A network blip can leave the socket half-open, so a server with nothing to send would never notice it is gone: a
     * quiet socket is pinged, and one that stays quiet as long as {@link SocketLiveness} allows is dropped and
     * reconnected.
     */
    private void checkAlive() {
        WebSocket open = socket;
        if (open == null) return;
        switch (liveness.check(clock.getAsLong())) {
            case PING -> {
                try {
                    open.sendPing(ByteBuffer.allocate(0)).whenComplete((sent, failure) -> {
                        if (failure != null) lost(open, "ping failed: " + failure);
                    });
                } catch (RuntimeException closing) {
                    lost(open, "ping failed: " + closing);
                }
            }
            case DEAD -> lost(open, "no reply to a ping");
            case NONE -> {}
        }
    }

    /**
     * {@link WebSocket} takes one message at a time, so only the sender thread writes, each after the last. A write
     * fails after 15 s, or once a retiring socket's time is up.
     */
    private void write(WebSocket open, byte[] message) throws Exception {
        try {
            CompletableFuture<WebSocket> sending = open.sendBinary(ByteBuffer.wrap(message), true);
            long giveUp = System.nanoTime() + WRITE_NANOS;
            while (true) {
                try {
                    sending.get(FLUSH_NANOS, TimeUnit.NANOSECONDS);
                    break;
                } catch (TimeoutException slow) {
                    if (overdue() || System.nanoTime() - giveUp > 0) throw slow;
                }
            }
        } catch (Exception failure) {
            lost(open, "send failed: " + failure);
            throw failure;
        } finally {
            pipeline.sent(message);
        }
    }

    /** Warns at most every 30 seconds while records are being refused. */
    private void logRefused() {
        long now = System.nanoTime();
        if (now - refusedLogNanos < REFUSED_LOG_NANOS) return;
        long total = refused.get();
        if (total != refusedLogged) {
            LogUtil.warn(String.format(
                    Locale.ROOT,
                    "Cloud send queue full: %d records refused in the last %d s; %s",
                    total - refusedLogged,
                    TimeUnit.NANOSECONDS.toSeconds(now - refusedLogNanos),
                    summary()));
        }
        refusedLogged = total;
        refusedLogNanos = now;
    }

    private void dropQueued() {
        Queued queued;
        while ((queued = queue.poll()) != null) queuedBytes.addAndGet(-queued.length());
    }

    /**
     * The socket went away: every stream of its generation stops until the next connect. {@code why} is null when this
     * connection closed it.
     */
    private synchronized void lost(WebSocket gone, String why) {
        if (socket != gone) return;
        lastLifeNanos = System.nanoTime() - openedNanos;
        socket = null;
        long ended = generation;
        if (why == null) LogUtil.info("Cloud socket closed (server id " + serverId + ")");
        else LogUtil.warn("Cloud socket closed: " + why + " (server id " + serverId + ")");
        gone.abort();
        diagnostics.socketClosed(ended);
        for (Stream stream : streams.values()) {
            try {
                stream.disconnected(ended);
            } catch (RuntimeException closing) {
            }
        }
        LockSupport.unpark(sender);
    }

    /** Gathers one reply message from its fragments, up to a limit. */
    static final class ReplyAssembler {
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
        private final int limit;

        ReplyAssembler(int limit) {
            this.limit = limit;
        }

        /** False, and what was gathered dropped, when the fragment would take the message past the limit. */
        boolean append(ByteBuffer data) {
            if (partial.size() + (long) data.remaining() > limit) {
                partial.reset();
                return false;
            }
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            partial.writeBytes(bytes);
            return true;
        }

        /** The whole message; the assembler starts the next one empty. */
        byte[] finish() {
            byte[] message = partial.toByteArray();
            partial.reset();
            return message;
        }
    }

    private final class Replies implements WebSocket.Listener {
        private final ReplyAssembler partial = new ReplyAssembler(MAX_REPLY_BYTES);
        private final long generation;

        private Replies(long generation) {
            this.generation = generation;
        }

        /** Asks for no reply until the socket is published; one read before then would be dropped. */
        @Override
        public void onOpen(WebSocket webSocket) {}

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            if (socket == webSocket && generation == CloudConnection.this.generation) liveness.pong(clock.getAsLong());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (socket != webSocket || generation != CloudConnection.this.generation) {
                webSocket.request(1);
                return null;
            }
            liveness.heard(clock.getAsLong());
            if (!partial.append(data)) {
                lost(webSocket, "reply over " + MAX_REPLY_BYTES + " bytes");
                return null;
            }
            if (last) {
                List<IntakeWire.Reply> replies = List.of();
                try {
                    replies = IntakeWire.replies(partial.finish());
                } catch (RuntimeException malformed) {
                    REPLY_FAILURES.warn("unreadable", "Cloud sent an unreadable reply", malformed);
                }
                for (IntakeWire.Reply reply : replies) {
                    try {
                        if (reply instanceof IntakeWire.Event event) {
                            events.incrementAndGet();
                            owner.finding(event);
                            continue;
                        }
                        if (reply instanceof IntakeWire.DiagLimitsReply limits) {
                            diagnostics.limits(generation, limits.limits());
                            continue;
                        }
                        Stream stream = streams.get(reply.player());
                        if (stream != null) stream.reply(generation, reply);
                        else if (reply instanceof IntakeWire.DiagCutReply cut)
                            diagnostics.cut(cut.player(), cut.cut(), null);
                    } catch (RuntimeException failure) {
                        REPLY_FAILURES.warn("handling", "Cloud reply could not be handled", failure);
                    }
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            if (status == TAKEN_OVER && socket == webSocket) {
                if (retiring) {
                    lost(webSocket, null);
                    return null;
                }
                takenOver = true;
                lost(
                        webSocket,
                        "taken over by another connection with this server id; connecting again in "
                                + TimeUnit.NANOSECONDS.toSeconds(takeoverNanos) + " s");
                return null;
            }
            lost(webSocket, status + (reason.isEmpty() ? "" : " " + LogThrottle.clip(reason)));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            lost(webSocket, String.valueOf(error));
        }
    }
}
