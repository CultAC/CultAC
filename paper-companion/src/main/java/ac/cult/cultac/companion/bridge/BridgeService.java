package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeAuth;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Auth;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Challenge;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Close;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Hello;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Listeners;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Ready;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Reject;
import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.bridge.protocol.BridgeProtocolException;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

/** The bridge core: handshake state machine, the READY session set and handler dispatch. */
final class BridgeService implements CompanionBridge {
    static final long SERVED = ServerCapability.mask(EnumSet.of(
            ServerCapability.TELEPORT,
            ServerCapability.GAMEMODE,
            ServerCapability.CLOSE_INVENTORY,
            ServerCapability.VALIDATION_BLOCK,
            ServerCapability.CONSOLE_EXEC,
            ServerCapability.WORLD_INFO,
            ServerCapability.SERVER_STATS,
            ServerCapability.CLEAR_ACTIVE_ITEM));
    private static final BridgeSession[] NONE = new BridgeSession[0];
    static final long AUTH_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final byte[] secret;
    private final BridgePlatform platform;
    private final Logger logger;
    private final boolean debug;
    private final List<CompanionMessageHandler> handlers = new CopyOnWriteArrayList<>();
    private final Map<UUID, BridgeSession> byPlayer = new ConcurrentHashMap<>();
    private final Set<Integer> unknownTypes = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private volatile BridgeSession[] ready = NONE;
    private volatile long capabilities = SERVED;
    private volatile int listenerBits;
    private boolean announcedActive;
    private final AtomicLong nextAuthWarn = new AtomicLong(System.nanoTime());
    private final AtomicBoolean versionWarned = new AtomicBoolean();

    BridgeService(byte[] secret, BridgePlatform platform, Logger logger, boolean debug) {
        this.secret = secret;
        this.platform = platform;
        this.logger = logger;
        this.debug = debug;
    }

    @Override
    public @Nullable CompanionSession session(UUID player) {
        return byPlayer.get(player);
    }

    @Override
    public @Nullable BridgeSession anySession() {
        BridgeSession[] current = ready;
        return current.length == 0 ? null : current[0];
    }

    @Override
    public void addHandler(CompanionMessageHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void setListenerBits(int listenerKindBits) {
        synchronized (lock) {
            if (listenerKindBits == listenerBits) return;
            listenerBits = listenerKindBits;
            Listeners message = new Listeners(listenerKindBits);
            for (BridgeSession session : ready) session.send(message);
        }
    }

    @Override
    public void addCapabilities(long capabilityMask) {
        synchronized (lock) {
            capabilities |= capabilityMask;
        }
    }

    void debug(String message) {
        if (debug) logger.info("[debug] " + message);
    }

    Logger logger() {
        return logger;
    }

    /** Handles one inbound frame on the endpoint's thread. */
    void receive(BridgeEndpoint endpoint, BridgeMessage message) {
        switch (message) {
            case Hello hello -> hello(endpoint, hello);
            case Auth auth -> auth(endpoint, auth);
            case Close close -> {
                BridgeSession session = endpoint.session;
                if (session == null) return;
                debug("Proxy closed the bridge for " + session.playerId() + ": " + close.reason());
                close(session);
            }
            default -> {
                BridgeSession session = endpoint.session;
                if (session == null || !session.isReady()) {
                    if (debug) debug("Ignoring " + message.getClass().getSimpleName() + " before READY");
                    return;
                }
                if (debug) debug("Received " + message.getClass().getSimpleName() + " for " + session.playerId());
                for (CompanionMessageHandler handler : handlers) {
                    try {
                        handler.onMessage(session, message);
                    } catch (Throwable failure) {
                        logger.log(
                                Level.WARNING,
                                "Bridge handler failed on " + message.getClass().getSimpleName(),
                                failure);
                    }
                }
            }
        }
    }

    void protocolError(BridgeProtocolException failure) {
        if (failure.isUnknownType()) {
            if (unknownTypes.add(failure.unknownType()))
                logger.warning("Ignoring unknown bridge message type " + failure.unknownType()
                        + "; the proxy speaks a newer bridge protocol");
        } else if (debug) {
            debug("Dropping malformed bridge frame: " + failure.getMessage());
        }
    }

    /** Handles the HELLO held for this connection; call on the endpoint's thread once the player has joined. */
    void attached(BridgeEndpoint endpoint) {
        Hello pending = endpoint.pendingHello;
        if (pending == null) return;
        endpoint.pendingHello = null;
        hello(endpoint, pending);
    }

    private void hello(BridgeEndpoint endpoint, Hello hello) {
        UUID player = endpoint.playerId();
        if (player == null) {
            endpoint.pendingHello = hello;
            debug("Holding HELLO until the player joins");
            return;
        }
        endpoint.pendingHello = null;
        BridgeSession previous = endpoint.session;
        if (previous != null
                && (previous.state == BridgeSession.AWAITING_AUTH || previous.state == BridgeSession.AUTHENTICATED)
                && Arrays.equals(previous.proxyNonce, hello.proxyNonce())) {
            endpoint.send(new Challenge(BridgeProtocol.VERSION, previous.backendNonce));
            return;
        }
        if (previous != null) close(previous);
        if (hello.version() != BridgeProtocol.VERSION) {
            if (versionWarned.compareAndSet(false, true))
                logger.warning("The CultAC proxy speaks bridge protocol " + hello.version() + " and this companion "
                        + BridgeProtocol.VERSION + "; install both from the same release");
            endpoint.send(new Reject("Bridge protocol " + hello.version() + " is not supported; this companion speaks "
                    + BridgeProtocol.VERSION));
            return;
        }
        if (!hello.player().equals(player)) {
            endpoint.send(new Reject("HELLO names a different player than this connection"));
            return;
        }
        BridgeSession session = new BridgeSession(endpoint, player, hello.proxyNonce(), BridgeAuth.nonce());
        endpoint.session = session;
        endpoint.send(new Challenge(BridgeProtocol.VERSION, session.backendNonce));
    }

    private void auth(BridgeEndpoint endpoint, Auth auth) {
        BridgeSession session = endpoint.session;
        if (session == null || session.state != BridgeSession.AWAITING_AUTH) {
            debug("Ignoring AUTH without a pending HELLO");
            return;
        }
        byte[] expected = BridgeAuth.hmac(secret, session.playerId(), session.proxyNonce, session.backendNonce);
        if (!BridgeAuth.verify(expected, auth.hmac())) {
            endpoint.send(new Reject("auth failed"));
            close(session);
            String warning = "Bridge authentication failed for " + session.playerId()
                    + ": the proxy's bridge secret differs from this server's";
            if (shouldWarnAuth(endpoint)) logger.warning(warning);
            else debug(warning);
            return;
        }
        session.state = BridgeSession.AUTHENTICATED;
        platform.runMain(() -> open(session));
    }

    /** Main thread; handlers see the session before READY goes out. */
    private void open(BridgeSession session) {
        if (session.state != BridgeSession.AUTHENTICATED) return;
        byte[] proof = BridgeAuth.backendHmac(secret, session.playerId(), session.backendNonce, session.proxyNonce);
        Ready body = platform.ready(session.playerId(), proof, capabilities, listenerBits);
        if (body == null) {
            close(session);
            return;
        }
        session.player = platform.player(session.playerId());
        for (CompanionMessageHandler handler : handlers) {
            try {
                handler.onReady(session);
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "Bridge handler failed on READY", failure);
            }
        }
        boolean opened;
        synchronized (lock) {
            opened = session.state == BridgeSession.AUTHENTICATED;
            if (opened) {
                if (body.capabilities() != capabilities || body.listeners() != listenerBits) {
                    body = new Ready(
                            body.hmac(),
                            capabilities,
                            body.serverVersion(),
                            body.worldUid(),
                            body.worldName(),
                            listenerBits);
                }
                session.state = BridgeSession.READY;
                session.endpoint.send(body);
                byPlayer.put(session.playerId(), session);
                BridgeSession[] grown = Arrays.copyOf(ready, ready.length + 1);
                grown[grown.length - 1] = session;
                ready = grown;
            }
        }
        if (!opened) {
            notifyClosed(session);
            return;
        }
        if (!announcedActive) {
            announcedActive = true;
            logger.info("proxy bridge active");
        }
        debug("Bridge session READY for " + session.playerId());
    }

    void endpointClosed(BridgeEndpoint endpoint) {
        endpoint.pendingHello = null;
        BridgeSession session = endpoint.session;
        if (session != null) close(session);
    }

    void close(UUID player) {
        BridgeSession session = byPlayer.get(player);
        if (session != null) close(session);
    }

    /** Tells the proxy every session is gone, then closes them. */
    void closeAll(String reason) {
        for (BridgeSession session : ready) {
            session.endpoint.send(new Reject(reason));
            close(session);
        }
    }

    /** At most once per connection and once per {@link #AUTH_WARN_INTERVAL_NANOS} across all connections. */
    private boolean shouldWarnAuth(BridgeEndpoint endpoint) {
        if (endpoint.authWarned) return false;
        endpoint.authWarned = true;
        long now = System.nanoTime();
        long next = nextAuthWarn.get();
        return now - next >= 0 && nextAuthWarn.compareAndSet(next, now + AUTH_WARN_INTERVAL_NANOS);
    }

    void close(BridgeSession session) {
        synchronized (lock) {
            int previous = session.state;
            if (previous == BridgeSession.CLOSED) return;
            session.state = BridgeSession.CLOSED;
            if (previous != BridgeSession.READY) return;
            byPlayer.remove(session.playerId(), session);
            BridgeSession[] current = ready;
            int index = -1;
            for (int i = 0; i < current.length; i++) if (current[i] == session) index = i;
            if (index >= 0) {
                BridgeSession[] shrunk = new BridgeSession[current.length - 1];
                System.arraycopy(current, 0, shrunk, 0, index);
                System.arraycopy(current, index + 1, shrunk, index, shrunk.length - index);
                ready = shrunk;
            }
        }
        platform.runMain(() -> notifyClosed(session));
    }

    private void notifyClosed(BridgeSession session) {
        for (CompanionMessageHandler handler : handlers) {
            try {
                handler.onClosed(session);
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "Bridge handler failed on session close", failure);
            }
        }
    }
}
