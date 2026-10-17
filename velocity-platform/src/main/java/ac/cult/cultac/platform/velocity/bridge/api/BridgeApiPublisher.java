package ac.cult.cultac.platform.velocity.bridge.api;

import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertToggle;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckCatalog;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckInfo;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Flag;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Listeners;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Metrics;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Reloaded;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Setback;
import ac.cult.cultac.bridge.protocol.BridgeMessage.UserInfo;
import ac.cult.cultac.bridge.protocol.ListenerKind;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.platform.velocity.bridge.BridgeFrames;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.alerts.AlertManager;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.event.events.GrimPlayerSetbackEvent;
import ac.grim.grimac.api.event.events.GrimReloadEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mirrors the proxy's Grim API state to backends whose companion exposes the Grim API.
 *
 * <p>Session callbacks run on the player's channel event loop; event handlers and {@link #tick()} hop onto it through
 * the executor resolved per user, so all per-session state is confined to that loop.
 */
public final class BridgeApiPublisher implements BridgeMessageListener {
    static final int MAX_NAME_CHARS = 1024;
    static final int MAX_DESCRIPTION_CHARS = 2048;

    /** Encoded-size budget of one CHECK_CATALOG chunk. */
    static final int CATALOG_CHUNK_BYTES = 30000;

    static final int PING_STEP_MS = 5;
    static final int PING_STEP_PERCENT = 10;

    /** Runs after every other FlagEvent handler, and is skipped once one of them cancelled. */
    static final int FLAG_PRIORITY = Integer.MAX_VALUE;

    private final Function<UUID, GrimUser> users;
    private final AlertManager alerts;
    private final GrimPlugin plugin;
    private final Function<GrimUser, Executor> loops;
    private final Logger logger;
    private final Map<UUID, Mirror> mirrors = new ConcurrentHashMap<>();
    private final FlagEvent.Channel flagChannel;
    private final GrimPlayerSetbackEvent.Channel setbackChannel;
    private final GrimReloadEvent.Channel reloadChannel;
    private final FlagEvent.SupplierHandler flagHandler = this::onFlag;
    private final GrimPlayerSetbackEvent.Handler setbackHandler = this::onSetback;
    private final GrimReloadEvent.Handler reloadHandler = this::onReload;
    private final Object subscriptionLock = new Object();
    private boolean flagSubscribed;
    private boolean setbackSubscribed;
    private volatile Predicate<GrimUser> flagOwned = user -> false;
    private volatile boolean closed;

    public BridgeApiPublisher(
            Function<UUID, GrimUser> users,
            AlertManager alerts,
            EventBus bus,
            GrimPlugin plugin,
            Function<GrimUser, Executor> loops,
            Logger logger) {
        this.users = users;
        this.alerts = alerts;
        this.plugin = plugin;
        this.loops = loops;
        this.logger = logger;
        this.flagChannel = bus.get(FlagEvent.class);
        this.setbackChannel = bus.get(GrimPlayerSetbackEvent.class);
        this.reloadChannel = bus.get(GrimReloadEvent.class);
    }

    /**
     * Subscribes to the proxy's reload event. The FlagEvent and GrimPlayerSetbackEvent handlers subscribe only while a
     * mirrored backend listens for them, so the proxy's channels stay empty otherwise.
     */
    public void subscribe() {
        reloadChannel.onReload(plugin, reloadHandler);
    }

    /**
     * Matches the FlagEvent and GrimPlayerSetbackEvent subscriptions to the listener bits of the mirrored sessions. The
     * flag handler subscribes at the highest priority without ignoreCancelled, so it only sees flags no proxy plugin
     * cancelled.
     */
    private void updateSubscriptions() {
        synchronized (subscriptionLock) {
            boolean flag = false;
            boolean setback = false;
            if (!closed) {
                for (Mirror mirror : mirrors.values()) {
                    int listeners = mirror.session.listeners();
                    flag |= ListenerKind.has(listeners, ListenerKind.FLAG);
                    setback |= ListenerKind.has(listeners, ListenerKind.SETBACK);
                }
            }
            if (flag != flagSubscribed) {
                flagSubscribed = flag;
                if (flag) flagChannel.onFlagSupplier(plugin, flagHandler, FLAG_PRIORITY, false);
                else flagChannel.unsubscribe(flagHandler);
            }
            if (setback != setbackSubscribed) {
                setbackSubscribed = setback;
                if (setback) setbackChannel.onPlayerSetback(plugin, setbackHandler);
                else setbackChannel.unsubscribe(setbackHandler);
            }
        }
    }

    /** Users whose flags another component sends to the backend; this mirror skips their FLAG notify. */
    public void flagOwnedBy(Predicate<GrimUser> owned) {
        this.flagOwned = owned;
    }

    /** The CHECK_CATALOG index of {@code check} on the session's backend, or -1. Safe from any thread. */
    public int checkIndex(BridgeSession session, AbstractCheck check) {
        Mirror mirror = mirrors.get(session.playerId());
        if (mirror == null || mirror.session != session) return -1;
        Integer idx = mirror.checkIndex.get(check);
        return idx == null ? -1 : idx;
    }

    public void close() {
        closed = true;
        updateSubscriptions();
        reloadChannel.unsubscribe(reloadHandler);
        mirrors.clear();
    }

    @Override
    public void onReady(BridgeSession session) {
        if (closed || !session.has(ServerCapability.GRIM_API)) return;
        GrimUser user = users.apply(session.playerId());
        if (user == null) return;
        Mirror mirror = new Mirror(session, user, loops.apply(user));
        mirrors.put(session.playerId(), mirror);
        mirror.sendUserInfo(true);
        mirror.sendCatalog();
        mirror.sendAlertState(true);
        mirror.sendMetrics(true);
        updateSubscriptions();
    }

    @Override
    public void onMessage(BridgeSession session, BridgeMessage message) {
        if (closed) return;
        Mirror mirror = mirrors.get(session.playerId());
        if (mirror == null || mirror.session != session) return;
        if (message instanceof Listeners) {
            updateSubscriptions();
            return;
        }
        if (!(message instanceof AlertToggle toggle)) return;
        switch (toggle.kind()) {
            case AlertToggle.ALERTS -> applyToggle(session, mirror, toggle, "cult.alerts", alerts::setAlertsEnabled);
            case AlertToggle.VERBOSE -> applyToggle(session, mirror, toggle, "cult.verbose", alerts::setVerboseEnabled);
            case AlertToggle.BRANDS -> applyToggle(session, mirror, toggle, "cult.brand", alerts::setBrandsEnabled);
            default -> {}
        }
    }

    /**
     * Enabling needs the proxy permission of the matching command. A refused or rate-limited toggle still gets the
     * ALERT_STATE reply, which corrects the backend's bits.
     */
    private void applyToggle(
            BridgeSession session, Mirror mirror, AlertToggle toggle, String permission, AlertSetter setter) {
        if (session.admitControl(false)) {
            try {
                if (!toggle.enabled() || mirror.user.hasPermission(permission)) {
                    setter.set(mirror.user, toggle.enabled(), toggle.silent());
                }
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "Unable to apply an alert toggle from " + session.serverName(), failure);
            }
        }
        mirror.sendAlertState(true);
    }

    private interface AlertSetter {
        void set(GrimUser user, boolean enabled, boolean silent);
    }

    @Override
    public void onClosed(BridgeSession session) {
        Mirror mirror = mirrors.get(session.playerId());
        if (mirror != null && mirror.session == session && mirrors.remove(session.playerId(), mirror)) {
            updateSubscriptions();
        }
    }

    /** Pushes METRICS, USER_INFO and ALERT_STATE where they changed; call once per second from any thread. */
    public void tick() {
        if (closed) return;
        for (Mirror mirror : mirrors.values()) mirror.loop.execute(mirror.refresh);
    }

    /** FLAG carries the violations the check holds once this flag commits its increment. */
    private boolean onFlag(GrimUser user, AbstractCheck check, Supplier<String> verbose, boolean cancelled) {
        if (cancelled || closed) return cancelled;
        Mirror mirror = mirrors.get(user.getUniqueId());
        if (mirror == null || !ListenerKind.has(mirror.session.listeners(), ListenerKind.FLAG)) return cancelled;
        if (flagOwned.test(mirror.user)) return cancelled;
        double violations = check.getViolations() + 1;
        String text = BridgeFrames.capVerbose(verbose.get());
        mirror.loop.execute(() -> mirror.sendFlag(check, violations, text));
        return cancelled;
    }

    private void onSetback(GrimUser user, int teleportId, double x, double y, double z, long timestamp) {
        if (closed) return;
        Mirror mirror = mirrors.get(user.getUniqueId());
        if (mirror == null || !ListenerKind.has(mirror.session.listeners(), ListenerKind.SETBACK)) return;
        mirror.loop.execute(() -> mirror.session.send(new Setback(teleportId, x, y, z)));
    }

    /** Every mirror re-sends its catalog; RELOADED reaches each backend server once, through one READY session. */
    private void onReload(boolean success) {
        if (closed) return;
        Set<String> announced = ConcurrentHashMap.newKeySet();
        for (Mirror mirror : mirrors.values()) {
            mirror.loop.execute(() -> {
                mirror.sendCatalog();
                BridgeSession session = mirror.session;
                if (session.isReady()
                        && ListenerKind.has(session.listeners(), ListenerKind.RELOAD)
                        && announced.add(session.serverName())) {
                    session.send(new Reloaded(success));
                }
            });
        }
    }

    /** True when {@code current} differs from {@code sent} by at least 5 ms and at least 10% of {@code sent}. */
    static boolean pingMoved(int sent, int current) {
        int delta = Math.abs(current - sent);
        return delta >= PING_STEP_MS && delta * 100L >= (long) Math.abs(sent) * PING_STEP_PERCENT;
    }

    static int encodedSize(CheckInfo info) {
        return 3
                + BridgeCodec.maxEncodedLength(info.checkName())
                + BridgeCodec.maxEncodedLength(info.configName())
                + BridgeCodec.maxEncodedLength(info.alternativeName())
                + BridgeCodec.maxEncodedLength(info.description())
                + BridgeCodec.maxEncodedLength(info.stableKey())
                + 2 * Double.BYTES
                + 2;
    }

    /** One READY session's mirrored state; touched only on the player's event loop. */
    private final class Mirror {
        final BridgeSession session;
        final GrimUser user;
        final Executor loop;
        final Runnable refresh = this::refresh;
        volatile Map<AbstractCheck, Integer> checkIndex = Map.of();
        int transactionPing;
        double sensX;
        double sensY;
        String brand;
        String versionName;
        boolean vanillaMath;
        int alertBits = -1;

        Mirror(BridgeSession session, GrimUser user, Executor loop) {
            this.session = session;
            this.user = user;
            this.loop = loop;
        }

        void refresh() {
            if (!session.isReady() || mirrors.get(session.playerId()) != this) return;
            try {
                sendMetrics(false);
                sendUserInfo(false);
                sendAlertState(false);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "Unable to mirror Grim API state for " + session.playerId(), failure);
            }
        }

        void sendCatalog() {
            Collection<? extends AbstractCheck> checks = user.getChecks();
            int size = Math.min(checks.size(), BridgeCodec.MAX_LIST_SIZE);
            List<CheckInfo> infos = new ArrayList<>(size);
            // Keys each check and the reload host's cached outward handle for it, which FlagEvent handlers receive.
            Map<AbstractCheck, Integer> index = new IdentityHashMap<>(size);
            for (AbstractCheck check : checks) {
                if (infos.size() == size) break;
                int idx = infos.size();
                index.put(check, idx);
                index.put(RuntimeEnvironment.outwardCheck(check), idx);
                infos.add(new CheckInfo(
                        idx,
                        BridgeFrames.cap(check.getCheckName(), MAX_NAME_CHARS),
                        BridgeFrames.cap(check.getConfigName(), MAX_NAME_CHARS),
                        BridgeFrames.cap(check.getAlternativeName(), MAX_NAME_CHARS),
                        BridgeFrames.cap(check.getDescription(), MAX_DESCRIPTION_CHARS),
                        BridgeFrames.cap(check.getStableKey(), MAX_NAME_CHARS),
                        check.getSetbackVL(),
                        check.getDecay(),
                        check.isExperimental(),
                        check.isEnabled()));
            }
            checkIndex = index;
            sendCatalogChunks(infos);
        }

        /** Splits the catalog in idx order; the companion starts a new catalog at the chunk holding idx 0. */
        void sendCatalogChunks(List<CheckInfo> infos) {
            int start = 0;
            int bytes = 0;
            for (int i = 0; i < infos.size(); i++) {
                int size = encodedSize(infos.get(i));
                if (i > start && bytes + size > CATALOG_CHUNK_BYTES) {
                    session.send(new CheckCatalog(infos.subList(start, i), false));
                    start = i;
                    bytes = 0;
                }
                bytes += size;
            }
            if (start < infos.size() || infos.isEmpty())
                session.send(new CheckCatalog(infos.subList(start, infos.size()), true));
        }

        void sendFlag(AbstractCheck check, double violations, String verbose) {
            Integer idx = checkIndex.get(check);
            if (idx == null) return;
            session.send(new Flag(BridgeFrames.NOTIFY_FLAG_ID, idx, violations, verbose, false));
        }

        void sendUserInfo(boolean force) {
            String brand = BridgeFrames.cap(user.getBrand(), MAX_NAME_CHARS);
            String versionName = BridgeFrames.cap(user.getVersionName(), MAX_NAME_CHARS);
            boolean vanillaMath = user.isVanillaMath();
            if (!force
                    && brand.equals(this.brand)
                    && versionName.equals(this.versionName)
                    && vanillaMath == this.vanillaMath) return;
            this.brand = brand;
            this.versionName = versionName;
            this.vanillaMath = vanillaMath;
            session.send(new UserInfo(brand, versionName, vanillaMath));
        }

        /**
         * Sends METRICS when forced, when the transaction ping moved from the last sent value (see {@link
         * #pingMoved}), or when a sensitivity changed. The transaction counters ride along and never trigger a send.
         */
        void sendMetrics(boolean force) {
            int transactionPing = user.getTransactionPing();
            double sensX = user.getHorizontalSensitivity();
            double sensY = user.getVerticalSensitivity();
            if (!force
                    && !pingMoved(this.transactionPing, transactionPing)
                    && Double.compare(sensX, this.sensX) == 0
                    && Double.compare(sensY, this.sensY) == 0) return;
            this.transactionPing = transactionPing;
            this.sensX = sensX;
            this.sensY = sensY;
            session.send(new Metrics(
                    transactionPing, sensX, sensY, user.getLastTransactionSent(), user.getLastTransactionReceived()));
        }

        void sendAlertState(boolean force) {
            // The alert manager reads toggles from the platform player, which an early join has not bound yet.
            if (user instanceof CultPlayer player && player.platformPlayer == null) return;
            int bits = (alerts.hasAlertsEnabled(user) ? AlertState.ALERTS : 0)
                    | (alerts.hasVerboseEnabled(user) ? AlertState.VERBOSE : 0)
                    | (alerts.hasBrandsEnabled(user) ? AlertState.BRANDS : 0);
            if (!force && bits == alertBits) return;
            alertBits = bits;
            session.send(new AlertState(bits));
        }
    }
}
