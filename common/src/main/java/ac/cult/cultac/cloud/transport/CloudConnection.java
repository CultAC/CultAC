package ac.cult.cultac.cloud.transport;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.capture.PlayerText;
import ac.cult.cultac.cloud.diag.DiagUpload;
import ac.cult.cultac.cloud.diag.DiagWire;
import ac.cult.cultac.cloud.diag.Diagnostics;
import ac.cult.cultac.cloud.diag.Limits;
import ac.cult.cultac.cloud.findings.FindingsLog;
import ac.cult.cultac.cloud.intake.v3.PerfLimits;
import ac.cult.cultac.cloud.perf.AgentInfo;
import ac.cult.cultac.cloud.perf.Perf;
import ac.cult.cultac.cloud.perf.PerfRecorder;
import ac.cult.cultac.cloud.perf.PerfSettings;
import ac.cult.cultac.cloud.perf.PerfWire;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.common.PropertiesUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.yaml.snakeyaml.Yaml;

/**
 * One server id's socket to the intake service. {@code POST /api/v1/connect} through the load balancer hands out a
 * replica's intake socket, which is then opened directly and kept, reconnecting with backoff when it drops.
 *
 * <p>Streams queue records from any thread; one virtual sender thread cuts them into messages of up to the size connect
 * allows once the service resolves their probe markers, and at most {@link #MAX_WRITE_BYTES} record bytes, which a
 * {@link CompressionPipeline} compresses on the pool every socket shares, and the sender writes out in the order cut,
 * so they reach the service in order. A message gathers what is queued for up to {@link Timing#batchNanos}, compressed
 * as it comes; a record that {@linkplain IntakeWire#endsMessage ends a message} goes out at the next flush. A new
 * socket starts a new generation:
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

        /** Hears the team sockets: on the sender thread when one opens, else on whichever thread closed or read it. */
        default TeamSocket.Listener team() {
            return TeamSocket.Listener.NONE;
        }
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
    /** How long a message stays open taking parts, each flush's records, before it ends and goes out. */
    private static final long BATCH_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** How long one message's write may take before the socket is given up. */
    private static final long WRITE_NANOS = TimeUnit.SECONDS.toNanos(15);
    /** How long the sender may take to send its open message before it drops its own socket. */
    private static final long END_NANOS = TimeUnit.SECONDS.toNanos(1);
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
    /**
     * Diagnostic and perf parts wait while more than this many record bytes, two messages' worth, are queued for the
     * socket.
     */
    static final long DIAG_LOW_WATERMARK = 2L * MAX_WRITE_BYTES;
    /**
     * The most bytes one reply message may gather across its fragments; past it the socket is dropped. A full message of
     * the most replies one may carry, captures and findings as the service sends them, fits several times over.
     */
    static final int MAX_REPLY_BYTES = 4 * 1024 * 1024;
    /** Players who left a connection whose late findings it still takes, the longest gone forgotten first. */
    static final int MAX_DEPARTED = 4096;

    private static final long DEPARTED_NANOS = TimeUnit.MILLISECONDS.toNanos(FindingsLog.RETENTION_MILLIS);

    private static final LogThrottle REPLY_FAILURES = new LogThrottle();
    private static final AtomicBoolean RADIUS_WARNED = new AtomicBoolean();
    /** The config files that decide what CultAC does, hashed for the service's record of each session. */
    private static final List<String> CONFIG_FILES = List.of("config.yml", "cult.yml", "punishments.yml");
    /** The config keys that hold credentials, which the config hash leaves out. */
    private static final List<List<String>> SECRET_KEYS =
            List.of(List.of("bridge", "secret"), List.of("cloud", "api-key"));

    private static final String SECRET_MARKER = "<secret>";

    private final URI connect;
    private final String apiKey;
    private final String serverId;
    private final SharedTransport shared;
    private final Owner owner;
    private final HttpClient http;
    private final Map<UUID, Stream> streams = new ConcurrentHashMap<>();
    /** When each player who left this connection left, oldest first; guarded by itself. */
    private final Map<UUID, Long> departed = new LinkedHashMap<>();

    /**
     * Any thread adds; only the sender thread takes while it runs, since {@link #nextPart} peeks before it polls.
     * Once the sender has {@link #stopped}, whoever adds drops what is left.
     */
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
    /** Whether connect offers perf profiles ({@code cloud.perf}). */
    private final boolean offerPerf;

    private final DiagUpload perfUpload = new DiagUpload();
    private final Perf perf;
    /** Whether connect offers team sync ({@code cloud.team-sync}). */
    private final boolean offerTeam;
    /** The open socket, while its connect answered {@code team}. */
    private volatile Team teamSocket;

    /** The socket of {@code generation} as a team socket. */
    private record Team(CloudConnection connection, long generation) implements TeamSocket {
        @Override
        public boolean send(byte[] record) {
            return connection.send(generation, record, 0);
        }

        @Override
        public String serverId() {
            return connection.serverId();
        }
    }

    private volatile WebSocket socket;
    private volatile long generation;
    private volatile int maxMessageBytes;
    /** The open socket's generation, the terms it was opened on, and the {@code textStripped} its connect said. */
    private volatile Agreed agreed = new Agreed(0, Terms.NONE, true);

    /** The agent this socket announced; a reload that changes its mode or config hash reconnects. */
    private volatile JsonObject announced;
    /** On reload, the sender compares the agent with the one announced. */
    private volatile boolean reloaded;
    /** When a stream last registered or unregistered, or the registry last handed this connection out. */
    private volatile long usedNanos;

    private final long idleAfterNanos;
    private final long batchNanos;
    private final Batch batch = new Batch();
    /** On the sender thread: whether it is ending the open message before dropping its socket. */
    private boolean ending;
    /** On the sender thread, while {@link #ending}: when it gives up and drops what is not yet sent. */
    private long endByNanos;
    /** The socket the sender closed after ending its open message, until the service answers or the sender gives up. */
    private volatile WebSocket closing;
    /** The socket the sender is ending its open message on, from end()'s snapshot until it is lost: sends refuse it. */
    private volatile WebSocket refusing;

    private volatile boolean closed;
    /** The sender thread no longer takes from {@link #queue}: set before its last drop. */
    private volatile boolean stopped;
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

        /** Whether the message that takes this entry ends with it. */
        boolean ends() {
            return IntakeWire.endsMessage(record);
        }
    }

    /**
     * What the service agreed to at connect: intake protocol 5, and the columns (Chebyshev) and the sections above and
     * below the player's within which its simulation world's sections stream.
     */
    public record Terms(int protocol, int chunkRadius, int sectionRadius) {
        /** No socket: nothing streams, terrain included. */
        public static final Terms NONE = new Terms(0, 0, 0);
        /** Protocol 5's radii when connect's answer leaves them out. */
        public static final int DEFAULT_CHUNK_RADIUS = 2;

        public static final int DEFAULT_SECTION_RADIUS = 2;
        /** The most either protocol 5 radius may be: past it a player's event loop would scan and send too much. */
        public static final int MAX_RADIUS = 8;

        /** Protocol 5's terms: sections within {@code chunkRadius} columns and {@code sectionRadius} sections. */
        public static Terms sections(int chunkRadius, int sectionRadius) {
            return new Terms(IntakeWire.PROTOCOL_5, chunkRadius, sectionRadius);
        }

        /** A socket agreed these terms: terrain goes out as sections of the simulation world. */
        public boolean sections() {
            return protocol == IntakeWire.PROTOCOL_5;
        }
    }

    private record Agreed(long generation, Terms terms, boolean textStripped) {}

    /**
     * The quiet and idle periods, the clock liveness, idleness, retirement, a message's age and the end of the open
     * message read, the wait after a takeover, and how long a message stays open.
     */
    record Timing(long quietNanos, long idleNanos, LongSupplier clock, long takeoverNanos, long batchNanos) {
        static final Timing DEFAULT = new Timing(QUIET_NANOS, IDLE_NANOS, System::nanoTime);

        Timing(long quietNanos, long idleNanos, LongSupplier clock) {
            this(quietNanos, idleNanos, clock, TAKEOVER_NANOS);
        }

        Timing(long quietNanos, long idleNanos, LongSupplier clock, long takeoverNanos) {
            this(quietNanos, idleNanos, clock, takeoverNanos, BATCH_NANOS);
        }
    }

    /**
     * The open message on the sender thread: its record bytes, its bytes once the service resolves its probe markers,
     * when its first part was cut, and whether it takes no more entries: the next would take it past a limit, or the
     * last one taken ends it.
     */
    static final class Batch {
        int bytes;
        long resolved;
        long startedNanos;
        boolean full;

        void clear() {
            bytes = 0;
            resolved = 0;
            full = false;
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
            boolean offerPerf,
            boolean offerTeam,
            SharedTransport shared,
            Owner owner,
            Timing timing) {
        this.connect = connect;
        this.apiKey = apiKey;
        this.serverId = serverId;
        this.protocols = List.copyOf(protocols);
        this.offerPerf = offerPerf;
        this.offerTeam = offerTeam;
        this.shared = shared;
        this.owner = owner;
        liveness = new SocketLiveness(timing.quietNanos());
        clock = timing.clock();
        idleAfterNanos = timing.idleNanos();
        takeoverNanos = timing.takeoverNanos();
        batchNanos = timing.batchNanos();
        usedNanos = clock.getAsLong();
        http = shared.http;
        queuedBytes = new QueuedBytes(SharedTransport.PROCESS_QUEUED);
        shared.acquire();
        int number = NUMBER.incrementAndGet();
        pipeline = new CompressionPipeline(shared.pool, shared.threads, queuedBytes);
        diagnostics = new Diagnostics(diagUpload, serverId);
        perf = new Perf(perfUpload, this::recordPerf);
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
        perf.close();
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

    /** Whether the sender, ending its open message before dropping its socket, is past {@link #END_NANOS}. */
    private boolean late() {
        return ending && clock.getAsLong() - endByNanos > 0;
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
        if (streams.remove(player, stream)) departed(player);
        touch();
    }

    private void departed(UUID player) {
        long now = clock.getAsLong();
        synchronized (departed) {
            departed.remove(player);
            departed.put(player, now);
            Iterator<Long> oldest = departed.values().iterator();
            while (oldest.hasNext()) {
                long left = oldest.next();
                if (departed.size() <= MAX_DEPARTED && now - left <= DEPARTED_NANOS) break;
                oldest.remove();
            }
        }
    }

    /** Whether the service may send findings for {@code player} here: joined now, or left within the retention. */
    private boolean joinedHere(UUID player) {
        if (streams.containsKey(player)) return true;
        synchronized (departed) {
            Long left = departed.get(player);
            return left != null && clock.getAsLong() - left <= DEPARTED_NANOS;
        }
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

    /** The terms the socket of {@code generation} was opened on; {@link Terms#NONE} once it is gone. */
    public Terms terms(long generation) {
        Agreed current = agreed;
        return current.generation() == generation ? current.terms() : Terms.NONE;
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
        WebSocket open = socket;
        if (closed || generation != this.generation || open == null || open == refusing) return false;
        if (!queuedBytes.tryAdd(record.length, MAX_QUEUED_BYTES, SharedTransport.MAX_PROCESS_QUEUED_BYTES)) {
            refused.incrementAndGet();
            return false;
        }
        queue.add(new Queued(generation, record, resolvedExtra));
        if (stopped) dropQueued();
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
                        // A lost socket's records are never sent: let go of them now, not at the next connect.
                        dropQueued();
                        pipeline.drop();
                        batch.clear();
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
                        pump(diagUpload);
                        pump(perfUpload);
                        flush(queue);
                        logRefused();
                        if (retiring && (queue.isEmpty() && !pipeline.open() || overdue())) {
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
                    LogUtil.warn("Cloud connection failed: " + redacted(String.valueOf(failure)));
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
        perf.close();
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
        stopped = true;
        dropQueued();
        try {
            pipeline.close();
        } catch (RuntimeException ignored) {
        }
        shared.release();
    }

    /** After a reload: drops the socket so the next connect announces the agent, when it differs from the one sent. */
    private void checkAgent() throws InterruptedException {
        if (!reloaded) return;
        reloaded = false;
        WebSocket open = socket;
        JsonObject sent = announced;
        if (open != null && sent != null && !agent().equals(sent)) {
            String why = "agent changed on reload";
            try {
                end(open, why);
            } finally {
                lost(open, why);
                if (refusing == open) refusing = null;
            }
        }
    }

    /** A codec that failed after connect said {@code textStripped} true: reconnect once to say false. */
    private void checkText() throws InterruptedException {
        WebSocket open = socket;
        if (open != null && agreed.textStripped() && !PlayerText.strips()) {
            String why = "a player text codec did not load; reconnecting with textStripped false";
            try {
                end(open, why);
            } finally {
                lost(open, why);
                if (refusing == open) refusing = null;
            }
        }
    }

    /**
     * Before the sender drops {@code open}, its own socket, because {@code why}: the open message ends and goes out with
     * what was queued before, then the socket closes normally and the sender waits for the service to answer the close,
     * which proves every message before it was read, all within {@link #END_NANOS}. Past that, or on a failed write,
     * what is unsent is dropped as a lost socket drops it, and the socket is lost saying so; from the snapshot on,
     * {@link #send} refuses records for the socket.
     */
    private void end(WebSocket open, String why) throws InterruptedException {
        refusing = open;
        Queue<Queued> before = new ArrayDeque<>();
        for (int left = queue.size(); left > 0; left--) {
            Queued queued = queue.poll();
            if (queued == null) break;
            before.add(queued);
        }
        ending = true;
        endByNanos = clock.getAsLong() + END_NANOS;
        boolean sent = false;
        boolean answered = false;
        Exception failed = null;
        try {
            flush(before);
            sent = socket == open && pipeline.idle();
            if (sent) {
                closing = open;
                open.sendClose(WebSocket.NORMAL_CLOSURE, "reconnecting");
                long left;
                while (closing == open && socket == open && (left = endByNanos - clock.getAsLong()) > 0) {
                    LockSupport.parkNanos(left);
                    if (Thread.interrupted()) throw new InterruptedException();
                }
                answered = closing != open;
            }
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception unsent) {
            failed = unsent;
        } finally {
            ending = false;
            closing = null;
            dropQueued(before);
        }
        if (answered || failed == null && socket != open) return;
        String dropped = failed != null
                ? sent
                        ? "its close failed after its last message was written; that message may be lost: " + failed
                        : "its last message was not sent: " + failed
                : sent
                        ? "the service did not answer its close within the end bound; its last message may be lost"
                        : "its open message was not sent within the end bound and is dropped";
        lost(open, why + "; " + dropped);
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
            JsonArray protocols = new JsonArray();
            for (int protocol : offered) protocols.add(protocol);
            body.add("protocols", protocols);
            boolean textStripped = PlayerText.stripsEvery();
            body.addProperty("textStripped", textStripped);
            body.addProperty("diag", DiagWire.VERSION);
            if (offerPerf) body.addProperty("perf", PerfWire.VERSION);
            if (offerTeam) body.addProperty("team", TeamSocket.VERSION);
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
                owner.unavailable(this, "connect took none of protocols " + offered + ": " + redacted(response.body()));
                return;
            }
            if (response.statusCode() != 200) {
                retry("connect answered " + response.statusCode() + ": " + response.body());
                return;
            }
            Connected connected = GSON.fromJson(response.body(), Connected.class);
            if (connected != null && connected.protocol != IntakeWire.PROTOCOL_5) {
                backOff("The cloud did not agree intake protocol 5; not streaming");
                return;
            }
            if (connected == null || connected.url == null) {
                retry("connect answered " + response.body());
                return;
            }
            if (!answerAllowed(connect, connected.url)) {
                retry("connect answered a socket URL that may not carry the key: " + origin(connected.url));
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
            diagnostics.connected(opened, connected.diagLimits());
            perf.connected(opened, offerPerf ? connected.perfLimits() : null);
            pipeline.stream();
            batch.clear();
            agreed = new Agreed(opened, terms, textStripped);
            generation = opened;
            announced = agent;
            reloaded = false;
            openedNanos = System.nanoTime();
            liveness.opened(clock.getAsLong());
            if (offerTeam) {
                // Before the socket is published, so the close of this socket always follows its open.
                Team team = new Team(this, opened);
                Boolean answered = connected.team == null ? null : connected.team.enabled;
                if (answered != null) teamSocket = team;
                try {
                    owner.team().opened(team, answered);
                } catch (RuntimeException failure) {
                    LogUtil.warn("Team sync could not take an opened socket", failure);
                }
            }
            socket = webSocket;
            webSocket.request(1);
            failing = false;
            LogUtil.info("Cloud connected to " + redacted(origin(connected.url)) + " on intake protocol "
                    + terms.protocol() + " (chunk radius " + terms.chunkRadius() + ", section radius "
                    + terms.sectionRadius() + ")");
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
     * The protocols to offer: protocol 5 when {@code configured} has it, and none when zstd, which it compresses with, is
     * unavailable. Empty, the upload stays off.
     */
    static List<Integer> protocols(List<Integer> configured, String zstdUnavailable) {
        return zstdUnavailable == null && configured.contains(IntakeWire.PROTOCOL_5)
                ? List.of(IntakeWire.PROTOCOL_5)
                : List.of();
    }

    /**
     * Whether the socket URL connect answered may be sent the key: {@code wss}, or {@code ws} to a loopback host when the
     * connect itself went over {@code http} to a loopback host, with a host and no user info.
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
            case "ws" ->
                "http".equalsIgnoreCase(connect.getScheme())
                        && CloudSettings.loopback(connect.getHost())
                        && CloudSettings.loopback(socket.getHost());
            default -> false;
        };
    }

    /** A URL the service names, for a log line: only its scheme, host and port, since its path may echo the key. */
    static String origin(String url) {
        try {
            URI uri = new URI(url);
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (URISyntaxException malformed) {
            return "an unreadable URL";
        }
    }

    /** {@code uri} as {@code scheme://host:port/path}, clipped, for a log line: never its user info or query. */
    static String loggable(URI uri) {
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return LogThrottle.clip(uri.getScheme() + "://" + uri.getHost() + port + path);
    }

    /** Reads a response body as UTF-8, failing once it passes {@code limit} bytes. */
    public static final class CappedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ReplyAssembler body;
        private final int limit;
        private Flow.Subscription subscription;

        public CappedBody(int limit) {
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
        /** Present when the service takes the perf offered: its limits. */
        PerfLimitsJson perf;
        /** Present when the service takes the team sync offered: the organization's state. */
        TeamStateJson team;

        /** The perf limits answered; null when the service took none. */
        PerfLimits perfLimits() {
            PerfLimitsJson limits = perf;
            if (limits == null) return null;
            return PerfLimits.newBuilder()
                    .setEnabled(limits.enabled)
                    .setMaxSeconds(uint32(limits.maxSeconds))
                    .setExecutionPeriodMs(uint32(limits.executionPeriodMs))
                    .setNativePeriodMs(uint32(limits.nativePeriodMs))
                    .setAllocationSamplesPerSecond(uint32(limits.allocationSamplesPerSecond))
                    .setPartBytes(uint32(limits.partBytes))
                    .setProfileBytes(uint32(limits.profileBytes))
                    .setUploadBytesPerSecond(uint32(limits.uploadBytesPerSecond))
                    .setWholeJvm(limits.wholeJvm)
                    .setSkipMsptMicros(uint32(limits.skipMsptMicros))
                    .build();
        }

        /** A JSON number as a proto uint32: a negative int is the same bits, anything out of range clamps. */
        private static int uint32(long value) {
            return (int) Math.clamp(value, Integer.MIN_VALUE, 0xFFFFFFFFL);
        }

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

        /** Whether the answer named a radius past {@link Terms#MAX_RADIUS}. */
        boolean radiusClamped() {
            return chunkRadius != null && chunkRadius > Terms.MAX_RADIUS
                    || sectionRadius != null && sectionRadius > Terms.MAX_RADIUS;
        }

        /** Protocol 5's terms, its radii defaulting when absent. */
        Terms terms() {
            return Terms.sections(
                    radius(chunkRadius, Terms.DEFAULT_CHUNK_RADIUS),
                    radius(sectionRadius, Terms.DEFAULT_SECTION_RADIUS));
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

    /** {@code ConnectResponse.perf} in connect's JSON. */
    static final class PerfLimitsJson {
        boolean enabled;
        long maxSeconds;
        long executionPeriodMs;
        long nativePeriodMs;
        long allocationSamplesPerSecond;
        long partBytes;
        long profileBytes;
        long uploadBytesPerSecond;
        boolean wholeJvm;
        long skipMsptMicros;
    }

    /** {@code ConnectResponse.team} in connect's JSON; {@code {}} is disabled. */
    static final class TeamStateJson {
        boolean enabled;
    }

    /** Which anticheat connects, from which build, with which config, and whether it acts on what it finds. */
    static JsonObject agent() {
        Properties build = PropertiesUtil.readProperties(CloudConnection.class, "cultac.properties");
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "cultac");
        agent.addProperty("version", buildProperty(build, "build.version"));
        agent.addProperty("commit", buildProperty(build, "git.commit"));
        agent.addProperty("configHash", configHash());
        agent.addProperty("mode", CultAPI.INSTANCE.getConfigManager().isObserveOnly() ? "observe-only" : "enforcing");
        return agent;
    }

    /**
     * One perf window on the perf thread, with the agent the open socket announced. The recorder is made here, so a JVM
     * without {@code jdk.jfr} fails the profile, never this connection.
     */
    private PerfRecorder.Result recordPerf(PerfSettings settings, BooleanSupplier cancelled) {
        JsonObject agent = announced;
        if (agent == null) agent = agent();
        AgentInfo info = new AgentInfo(
                CultAPI.INSTANCE.getPlatform() == Platform.VELOCITY ? "velocity" : "paper",
                serverId,
                string(agent, "commit"),
                string(agent, "version"),
                string(agent, "configHash"),
                string(agent, "mode"));
        return new PerfRecorder(CultAPI.INSTANCE.getPlatformServer(), info, Clock.systemUTC())
                .record(settings, cancelled);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
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
     * bundled default in {@code language} (else English) that it falls back to; null when one cannot be read. Each is
     * hashed as its parsed YAML written back out, every {@link #SECRET_KEYS} value replaced by one marker, so the hash
     * tells nothing about a secret, its length included.
     */
    static String configHash(File folder, String language) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String name : CONFIG_FILES) {
                File file = new File(folder, name);
                byte[] raw = file.isFile() ? Files.readAllBytes(file.toPath()) : bundledDefault(name, language);
                byte[] content = withoutSecrets(raw);
                digest.update((name + '\n' + content.length + '\n').getBytes(StandardCharsets.UTF_8));
                digest.update(content);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unreadable) {
            return null;
        }
    }

    /** {@code yaml} parsed and written back out, each of {@link #SECRET_KEYS} that is present set to a marker. */
    private static byte[] withoutSecrets(byte[] yaml) {
        Object tree = new Yaml().load(new String(yaml, StandardCharsets.UTF_8));
        if (tree instanceof Map<?, ?> root) {
            for (List<String> path : SECRET_KEYS) redact(root, path);
        }
        return new Yaml().dump(tree).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void redact(Map<?, ?> root, List<String> path) {
        Object node = root;
        for (int i = 0; i < path.size() - 1; i++) {
            if (!(node instanceof Map<?, ?> map)) return;
            node = map.get(path.get(i));
        }
        if (node instanceof Map<?, ?> parent && parent.containsKey(path.get(path.size() - 1))) {
            ((Map<Object, Object>) parent).put(path.get(path.size() - 1), SECRET_MARKER);
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
        backOff("Cloud connection to " + loggable(connect) + " failed: " + redacted(why) + "; retrying");
    }

    /** A connect failed: {@code line} is logged once per run of failures, and the next connect waits out the backoff. */
    private void backOff(String line) {
        if (!failing) LogUtil.warn(line);
        failing = true;
        await(backoff.failed());
    }

    /** Text from the transport or the service made safe to log: the key masked, then clipped. */
    private String redacted(String text) {
        return LogThrottle.clip(apiKey.isEmpty() || text == null ? text : text.replace(apiKey, "<api-key>"));
    }

    /** Parks the sender for {@code wait}, or until the connection closes or retires. */
    private void await(Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        long left;
        while (!closed && !retiring && !frozen && (left = end - System.nanoTime()) > 0) LockSupport.parkNanos(left);
    }

    /**
     * Compresses everything on {@code from} for the current socket into the open message, in parts {@link #nextPart}
     * cuts, and sends each message once it is full or has been open {@link Timing#batchNanos}, compressing later parts
     * on the pipeline while earlier messages are sent. A retiring socket, or one the sender is about to drop, ends its
     * open message at once. What is dropped is dropped before it is compressed; on protocol 5 a compressed message that
     * cannot be sent closes the socket.
     */
    private void flush(Queue<Queued> from) throws Exception {
        WebSocket open = socket;
        if (open == null) return;
        long current = generation;
        int resolvedLimit = messageLimit();
        int limit = Math.min(resolvedLimit, MAX_WRITE_BYTES);
        List<byte[]> part = new ArrayList<>();
        int bytes = 0;
        try {
            while (true) {
                while (pipeline.hasRoom() && !overdue() && !late()) {
                    bytes = nextPart(from, queuedBytes, current, limit, resolvedLimit, batch, part);
                    if (bytes == 0 && !pipeline.open()) break;
                    if (!pipeline.open()) batch.startedNanos = clock.getAsLong();
                    boolean end =
                            batch.full || retiring || ending || clock.getAsLong() - batch.startedNanos >= batchNanos;
                    if (bytes == 0 && !end) break;
                    pipeline.submit(part, bytes, end);
                    part.clear();
                    bytes = 0;
                    if (!end) break;
                    batch.clear();
                }
                if (overdue() || late()) return;
                byte[] next = ending ? pipeline.take(endByNanos - clock.getAsLong()) : pipeline.take();
                if (next == null) return;
                write(open, next);
                logRefused();
            }
        } catch (Exception failure) {
            queuedBytes.addAndGet(-bytes);
            batch.clear();
            // While ending, end() loses the socket; the sender drops the rest once it is gone, outside the end bound.
            if (!ending) {
                pipeline.drop();
                // The service cannot decompress a stream past a message it never got.
                lost(open, "a message was not sent: " + failure);
            }
            throw failure;
        }
    }

    /**
     * Takes the next part of {@code batch}, the open message for the socket of {@code generation}, off {@code queue}
     * into {@code part}: whole entries in order, the message at most {@code limit} record bytes and {@code resolvedLimit}
     * bytes once the service resolves their probe markers, or a single entry past either. An entry that would take it
     * past either stays queued and marks the batch full, as does one that {@linkplain Queued#ends ends} the message once
     * taken. Entries of other sockets are dropped. Returns the part's record
     * bytes, 0 when nothing is queued for the socket or the batch is full.
     */
    static int nextPart(
            Queue<Queued> queue,
            QueuedBytes queuedBytes,
            long generation,
            int limit,
            long resolvedLimit,
            Batch batch,
            List<byte[]> part) {
        int bytes = 0;
        Queued queued;
        while (!batch.full && (queued = queue.peek()) != null) {
            int length = queued.length();
            if (queued.generation() != generation) {
                queue.poll();
                queuedBytes.addAndGet(-length);
                continue;
            }
            if (batch.bytes > 0
                    && (batch.bytes + length > limit || batch.resolved + queued.resolvedLength() > resolvedLimit)) {
                batch.full = true;
                break;
            }
            queue.poll();
            part.add(queued.record());
            bytes += length;
            batch.bytes += length;
            batch.resolved += queued.resolvedLength();
            if (queued.ends()) batch.full = true;
        }
        return bytes;
    }

    /**
     * Moves an upload's parts onto the socket's queue while it is nearly drained and the upload bucket allows, so they
     * never hold up a frame. A part the queue refuses is lost: the service seals a bundle without it and refuses a
     * profile with a gap.
     */
    private void pump(DiagUpload upload) {
        if (!upload.pending()) return;
        long current = generation;
        while (queuedBytes.get() <= DIAG_LOW_WATERMARK) {
            byte[] part = upload.poll(current, System.nanoTime());
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
     * fails after 15 s, once a retiring socket's time is up, or, while the sender ends its open message, at
     * {@link #endByNanos}.
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
                    if (overdue() || late() || System.nanoTime() - giveUp > 0) throw slow;
                }
            }
        } catch (Exception failure) {
            if (!ending) lost(open, "send failed: " + failure);
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
        dropQueued(queue);
    }

    private void dropQueued(Queue<Queued> from) {
        Queued queued;
        while ((queued = from.poll()) != null) queuedBytes.addAndGet(-queued.length());
    }

    /**
     * The socket went away: every stream of its generation stops until the next connect, and the woken sender drops
     * the queue. {@code why} is null when this connection closed it. Any thread.
     */
    private synchronized void lost(WebSocket gone, String why) {
        if (socket != gone) return;
        lastLifeNanos = System.nanoTime() - openedNanos;
        socket = null;
        refusing = null;
        long ended = generation;
        if (why == null) LogUtil.info("Cloud socket closed (server id " + serverId + ")");
        else LogUtil.warn("Cloud socket closed: " + redacted(why) + " (server id " + serverId + ")");
        gone.abort();
        diagnostics.socketClosed(ended);
        perf.socketClosed(ended);
        Team team = teamSocket;
        teamSocket = null;
        if (team != null) {
            try {
                owner.team().closed(team);
            } catch (RuntimeException failure) {
                LogUtil.warn("Team sync could not take a closed socket", failure);
            }
        }
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
                    replies = IntakeWire.replies(partial.finish(), apiKey);
                } catch (RuntimeException malformed) {
                    REPLY_FAILURES.warn("unreadable", "Cloud sent an unreadable reply", malformed);
                }
                for (IntakeWire.Reply reply : replies) {
                    try {
                        if (reply instanceof IntakeWire.Event event) {
                            events.incrementAndGet();
                            if (joinedHere(event.player())) owner.finding(event);
                            continue;
                        }
                        if (reply instanceof IntakeWire.DiagLimitsReply limits) {
                            diagnostics.limits(generation, limits.limits());
                            continue;
                        }
                        if (reply instanceof IntakeWire.PerfLimitsReply limits) {
                            perf.limits(generation, limits.limits());
                            continue;
                        }
                        if (reply instanceof IntakeWire.PerfStartReply start) {
                            perf.start(generation, start.profile(), start.seconds());
                            continue;
                        }
                        if (reply instanceof IntakeWire.TeamStateReply state) {
                            Team team = teamSocket;
                            if (team != null && team.generation() == generation) {
                                owner.team().state(team, state.enabled());
                            }
                            continue;
                        }
                        if (reply instanceof IntakeWire.TeamSweepReply sweep) {
                            Team team = teamSocket;
                            if (team != null && team.generation() == generation) {
                                owner.team().sweep(team, sweep.players());
                            }
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
            if (webSocket == closing && status == WebSocket.NORMAL_CLOSURE) {
                closing = null;
                LockSupport.unpark(sender);
                return null;
            }
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
