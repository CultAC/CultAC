package ac.cult.cultac.platform.velocity.bridge;

import ac.cult.cultac.bridge.protocol.BridgeAuth;
import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Auth;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Challenge;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClearActiveItem;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Close;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Hello;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Listeners;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Marker;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerStateResult;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Ready;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Reject;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Result;
import ac.cult.cultac.bridge.protocol.BridgeMessage.World;
import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/** One player's bridge to the companion on one backend connection; all mutable state is confined to its loop. */
final class ProxyBridgeSession implements BridgeSession {
    static final long HELLO_RETRY_MILLIS = 500;
    /** HELLO resends after the first, each waiting twice as long as the one before. */
    static final int HELLO_RETRIES = 3;

    static final long READY_TIMEOUT_MILLIS = 10_000;
    static final int QUEUE_LIMIT = 256;

    private record Pending(CompletableFuture<@Nullable BridgeMessage> future, ScheduledFuture<?> timeout) {}

    private final UUID playerId;
    private final Object connection;
    private final BridgeBackend backend;
    private final String serverName;
    private final byte[] secret;
    private final BridgeService service;
    private final long helloRetryMillis;
    private final long readyTimeoutMillis;
    private final ArrayDeque<BridgeMessage> queue = new ArrayDeque<>();
    private final Map<Integer, Pending> pending = new HashMap<>();
    private volatile State state = State.HANDSHAKE;
    private volatile long capabilities;
    private volatile int listeners;
    private volatile @Nullable UUID worldUid;
    private volatile @Nullable String worldName;
    private volatile String serverVersion = "";
    private byte[] proxyNonce;
    private byte[] backendNonce;
    private boolean challenged;
    private int helloRetries;
    private boolean queueOverflowLogged;
    private boolean oversizeLogged;
    private final LongSupplier nanoClock;
    private final ControlBudget playerControl;
    private final ControlBudget consoleControl;
    private boolean playerLimitLogged;
    private boolean consoleLimitLogged;
    private int nextRequestId = 1;
    private @Nullable ScheduledFuture<?> helloRetry;
    private @Nullable ScheduledFuture<?> readyTimeout;

    ProxyBridgeSession(
            UUID playerId,
            Object connection,
            BridgeBackend backend,
            byte[] secret,
            BridgeService service,
            long helloRetryMillis,
            long readyTimeoutMillis,
            LongSupplier nanoClock) {
        this.playerId = playerId;
        this.connection = connection;
        this.backend = backend;
        this.serverName = backend.serverName();
        this.secret = secret.clone();
        this.service = service;
        this.helloRetryMillis = helloRetryMillis;
        this.readyTimeoutMillis = readyTimeoutMillis;
        this.nanoClock = nanoClock;
        long now = nanoClock.getAsLong();
        this.playerControl = new ControlBudget(now);
        this.consoleControl = new ControlBudget(now);
    }

    /** The backend connection this session belongs to. */
    Object connection() {
        return connection;
    }

    @Override
    public String serverVersion() {
        return serverVersion;
    }

    void start() {
        onLoop(() -> {
            if (state != State.HANDSHAKE || proxyNonce != null) return;
            proxyNonce = BridgeAuth.nonce();
            sendHello();
            scheduleHelloRetry();
            readyTimeout = backend.eventLoop().schedule(this::readyTimedOut, readyTimeoutMillis, TimeUnit.MILLISECONDS);
        });
    }

    void receive(BridgeMessage message) {
        onLoop(() -> {
            switch (state) {
                case HANDSHAKE -> handshake(message);
                case READY -> live(message);
                case CLOSED -> {}
            }
        });
    }

    void close() {
        onLoop(() -> closeNow(null));
    }

    /** Closes the session and, if the companion may consider it live, tells it why. */
    void close(String reason) {
        onLoop(() -> closeNow(reason));
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public boolean has(ServerCapability capability) {
        return state == State.READY && ServerCapability.has(capabilities, capability);
    }

    @Override
    public int listeners() {
        return listeners;
    }

    @Override
    public UUID playerId() {
        return playerId;
    }

    @Override
    public String serverName() {
        return serverName;
    }

    @Override
    public void send(BridgeMessage message) {
        Objects.requireNonNull(message);
        boolean ordered = message instanceof ClearActiveItem || message instanceof Marker;
        if (ordered && !backend.eventLoop().inEventLoop()) return;
        onLoop(() -> {
            if (state == State.CLOSED) return;
            if (state == State.READY && backend.inPlay()) {
                flush();
                write(message);
            } else if (!ordered) {
                enqueue(message);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> request(IntFunction<BridgeMessage> withRequestId, long timeoutMillis) {
        return exchange(withRequestId, timeoutMillis)
                .thenApply(reply -> reply instanceof Result result && result.success());
    }

    @Override
    public CompletableFuture<@Nullable BridgeMessage> exchange(
            IntFunction<BridgeMessage> withRequestId, long timeoutMillis) {
        var future = new CompletableFuture<@Nullable BridgeMessage>();
        boolean accepted = onLoop(() -> {
            if (state == State.CLOSED) {
                future.complete(null);
                return;
            }
            int id = nextRequestId++;
            BridgeMessage message;
            try {
                message = withRequestId.apply(id);
            } catch (RuntimeException invalid) {
                future.completeExceptionally(invalid);
                return;
            }
            var timeout = backend.eventLoop()
                    .schedule(
                            () -> {
                                if (pending.remove(id) != null) future.complete(null);
                            },
                            timeoutMillis,
                            TimeUnit.MILLISECONDS);
            pending.put(id, new Pending(future, timeout));
            send(message);
        });
        if (!accepted) future.complete(null);
        return future;
    }

    @Override
    public @Nullable UUID worldUid() {
        return worldUid;
    }

    @Override
    public @Nullable String worldName() {
        return worldName;
    }

    private void handshake(BridgeMessage message) {
        switch (message) {
            case Challenge challenge -> {
                if (challenged) return;
                if (challenge.version() != BridgeProtocol.VERSION) {
                    service.onRejected(
                            this,
                            "companion speaks bridge protocol " + challenge.version() + ", this proxy speaks "
                                    + BridgeProtocol.VERSION);
                    closeNow(null);
                    return;
                }
                challenged = true;
                backendNonce = challenge.backendNonce();
                cancel(helloRetry);
                write(new Auth(BridgeAuth.hmac(secret, playerId, proxyNonce, backendNonce)));
            }
            case Ready ready -> {
                if (!challenged) return;
                if (!BridgeAuth.verify(
                        BridgeAuth.backendHmac(secret, playerId, backendNonce, proxyNonce), ready.hmac())) {
                    service.onAuthFailed(this);
                    closeNow("backend failed authentication");
                    return;
                }
                capabilities = ready.capabilities();
                listeners = ready.listeners();
                worldUid = ready.worldUid();
                worldName = ready.worldName();
                serverVersion = ready.serverVersion();
                state = State.READY;
                cancel(helloRetry);
                cancel(readyTimeout);
                if (backend.inPlay()) flush();
                service.onReady(this);
            }
            case Reject reject -> {
                service.onRejected(this, reject.reason());
                closeNow(null);
            }
            default -> {}
        }
    }

    private void live(BridgeMessage message) {
        switch (message) {
            case World world -> {
                worldUid = world.uid();
                worldName = world.name();
            }
            case Listeners update -> listeners = update.listeners();
            case Reject reject -> {
                service.onRejected(this, reject.reason());
                closeNow(null);
                return;
            }
            case Result result -> reply(result.requestId(), result);
            case PlayerStateResult result -> reply(result.requestId(), result);
            default -> {}
        }
        service.onMessage(this, message);
    }

    @Override
    public boolean admitControl(boolean console) {
        if ((console ? consoleControl : playerControl).take(nanoClock.getAsLong())) return true;
        if (console ? !consoleLimitLogged : !playerLimitLogged) {
            if (console) consoleLimitLogged = true;
            else playerLimitLogged = true;
            service.warn(
                    "Refusing " + (console ? "console" : "player") + " command, suggestion and alert toggle frames "
                            + "over " + ControlBudget.BURST + " per burst from " + serverName + " for " + playerId);
        }
        return false;
    }

    private void reply(int requestId, BridgeMessage reply) {
        var request = pending.remove(requestId);
        if (request != null) {
            request.timeout().cancel(false);
            request.future().complete(reply);
        }
    }

    private void sendHello() {
        write(new Hello(BridgeProtocol.VERSION, playerId, proxyNonce));
    }

    private void scheduleHelloRetry() {
        long delay = helloRetryMillis << helloRetries;
        helloRetry = backend.eventLoop().schedule(this::retryHello, delay, TimeUnit.MILLISECONDS);
    }

    private void retryHello() {
        if (state != State.HANDSHAKE || challenged) return;
        sendHello();
        if (++helloRetries < HELLO_RETRIES) scheduleHelloRetry();
    }

    private void readyTimedOut() {
        if (state != State.HANDSHAKE) return;
        service.onNoCompanion(this);
        closeNow("no READY in time");
    }

    private void enqueue(BridgeMessage message) {
        if (queue.size() >= QUEUE_LIMIT) {
            queue.pollFirst();
            if (!queueOverflowLogged) {
                queueOverflowLogged = true;
                service.warn(
                        "Bridge queue for " + playerId + " on " + serverName + " is full; dropping oldest messages");
            }
        }
        queue.addLast(message);
    }

    private void flush() {
        while (state == State.READY && !queue.isEmpty()) write(queue.pollFirst());
    }

    private void write(BridgeMessage message) {
        byte[] frame;
        try {
            frame = BridgeCodec.toBytes(message);
        } catch (IllegalArgumentException unencodable) {
            service.warn("Dropping bridge message type " + message.type() + " for " + playerId + ": "
                    + unencodable.getMessage());
            return;
        }
        if (frame.length > BridgeFrames.MAX_BACKEND_FRAME_BYTES) {
            if (!oversizeLogged) {
                oversizeLogged = true;
                service.warn("Dropping a " + frame.length + "-byte bridge message of type " + message.type() + " for "
                        + playerId + " on " + serverName + "; backends accept at most "
                        + BridgeFrames.MAX_BACKEND_FRAME_BYTES);
            }
            return;
        }
        try {
            backend.write(frame);
        } catch (IllegalStateException disconnected) {
            closeNow(null);
        }
    }

    /** {@code notify} non-null sends CLOSE when the companion has seen AUTH and the backend is still in PLAY. */
    private void closeNow(@Nullable String notify) {
        if (state == State.CLOSED) return;
        boolean wasReady = state == State.READY;
        state = State.CLOSED;
        if (notify != null && challenged && backend.inPlay()) write(new Close(notify));
        cancel(helloRetry);
        cancel(readyTimeout);
        queue.clear();
        for (var request : pending.values()) {
            request.timeout().cancel(false);
            request.future().complete(null);
        }
        pending.clear();
        service.onClosed(this, wasReady);
    }

    /** Runs or schedules the task on the session's loop; false when that loop has shut down. */
    private boolean onLoop(Runnable task) {
        var loop = backend.eventLoop();
        if (loop.inEventLoop()) {
            task.run();
            return true;
        }
        try {
            loop.execute(task);
            return true;
        } catch (RejectedExecutionException shutdown) {
            return false;
        }
    }

    private static void cancel(@Nullable ScheduledFuture<?> task) {
        if (task != null) task.cancel(false);
    }
}
