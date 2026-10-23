package ac.cult.cultac.cloud;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.cloud.Cloud;
import ac.cult.cultac.cloud.capture.PlayerText;
import ac.cult.cultac.cloud.capture.Sections;
import ac.cult.cultac.cloud.diag.Diagnostics;
import ac.cult.cultac.cloud.findings.Finding;
import ac.cult.cultac.cloud.findings.FindingsLog;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.transport.CloudConnections;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.jetbrains.annotations.Nullable;

/**
 * The upload's lifecycle: reads its settings, opens and closes the intake sockets, installs every player's
 * {@link CloudCapture} and reports its status. Off until {@code cloud.url} and {@code cloud.api-key} are set.
 */
public final class CloudService {
    private static final CloudConnections CONNECTIONS = new CloudConnections();
    /** Captures with a channel, so a reload or a plugin disable reaches every one of them. */
    private static final Set<CloudCapture> INSTALLED = ConcurrentHashMap.newKeySet();

    private static final ConnectionUplinks UPLINKS = new ConnectionUplinks(CONNECTIONS);
    private static final Object CONFIGURING = new Object();
    /** The turn-on hook ran last, not the turn-off hook; guarded by {@link #CONFIGURING}. */
    private static boolean hooksOn;
    /** The findings the service sent, while the upload is configured on. */
    private static volatile @Nullable FindingsLog findings;

    static {
        CONNECTIONS.onTurnedOff(CloudService::turnedOff);
        CONNECTIONS.onFinding(CloudService::finding);
    }

    private CloudService() {}

    /** Sends what every team socket hears to team sync. */
    static void listenForTeam() {
        CONNECTIONS.onTeam(CultAPI.INSTANCE.getPortalSync().teamListener());
    }

    /** Applies the config: sockets open, close or move, and every player's capture follows. Any thread. */
    static void configReloaded() {
        configure(CultAPI.INSTANCE.getConfigManager().getConfig(), CultAPI.INSTANCE.getPlatform() == Platform.VELOCITY);
    }

    /**
     * {@code proxy} picks a proxy's protocols and server-id default over a server's own. Calls run one at a time, each
     * reading the config inside, so the last to run applies the latest config.
     */
    static void configure(ConfigManager config, boolean proxy) {
        synchronized (CONFIGURING) {
            configureNow(config, proxy);
        }
    }

    private static void configureNow(ConfigManager config, boolean proxy) {
        CloudSettings settings = CloudSettings.read(
                config, CloudSettings.PROTOCOLS, proxy ? CloudSettings.DEFAULT_SERVER_ID : CloudSettings.OWN_SERVER_ID);
        if (!config.getBooleanElse("cloud.enabled", true)) {
            LogUtil.info("Cloud upload is off: cloud.enabled is false");
            settings = CloudSettings.OFF;
        } else if (settings.url().isEmpty() || settings.apiKey().isEmpty()) {
            LogUtil.info("Cloud upload is off: cloud.url and cloud.api-key are not both set");
            settings = CloudSettings.OFF;
        } else if (settings.serverIdTemplate().contains("{proxy}")
                && settings.proxyId().isEmpty()) {
            LogUtil.warn("Cloud upload is off: cloud.server-id uses {proxy}, and cloud.proxy-id is empty");
            settings = CloudSettings.OFF;
        } else if (!proxy && settings.proxyId().isEmpty()) {
            LogUtil.warn("Cloud upload is off: set cloud.proxy-id to this server's name");
            settings = CloudSettings.OFF;
        }
        if (settings.enabled()) CloudCapture.warm();
        if (!settings.enabled()) findings = null;
        else if (findings == null) findings = new FindingsLog(System::currentTimeMillis, CloudService::tracked);
        CONNECTIONS.configure(settings);
        CloudCapture.uplinks(UPLINKS);
        boolean on = UPLINKS.enabled();
        if (on && !hooksOn) {
            hooksOn = true;
            hook("turn-on", CloudModule.turnedOn());
        }
        reconfigureCaptures();
        if (!on) turnOffHook();
    }

    /**
     * The service or zstd turned the upload off, on the thread that did. A notice that arrives after a configure has
     * turned it on again, or has already run the turn-off hook, changes nothing.
     */
    static void turnedOff() {
        synchronized (CONFIGURING) {
            if (UPLINKS.enabled()) return;
            reconfigureCaptures();
            turnOffHook();
        }
    }

    private static void turnOffHook() {
        if (!hooksOn) return;
        hooksOn = false;
        hook("turn-off", CloudModule.turnedOff());
    }

    private static void hook(String which, Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException failure) {
            LogUtil.warn("Cloud upload " + which + " hook failed", failure);
        }
    }

    /** Every player's capture follows the upload: it is made and installs, moves socket or leaves. */
    private static void reconfigureCaptures() {
        Set<CloudCapture> installed = new HashSet<>(INSTALLED);
        for (CloudCapture capture : installed) capture.reconfigure();
        for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            CloudCapture capture = CloudCapture.of(player);
            if (!installed.contains(capture) && (capture != null || UPLINKS.enabled()))
                CloudCapture.reconfigure(player);
        }
    }

    /**
     * {@code cloud.proxy-id} as the current settings have it: a server's own name, which its captures upload under;
     * null when it is empty. Any thread.
     */
    public static @Nullable String ownServerName() {
        String name = CONNECTIONS.settings().proxyId();
        return name.isEmpty() ? null : name;
    }

    /** Whether the upload is on and nothing has turned it off since. Any thread. */
    public static boolean enabled() {
        return UPLINKS.enabled();
    }

    /**
     * {@code player} is tracked: their kept findings are no longer gone, and their capture is made and installs when the
     * upload is on. Any thread.
     */
    static void playerRegistered(CultPlayer player) {
        FindingsLog log = findings;
        if (log != null) log.joined(player.getUniqueId());
        if (!UPLINKS.enabled()) return;
        CloudCapture.reconfigure(player);
    }

    /** {@code player} is no longer tracked: their findings are kept for {@link FindingsLog#RETENTION_MILLIS}. */
    static void playerRemoved(UUID player) {
        FindingsLog log = findings;
        if (log != null) log.left(player);
    }

    /** The findings the service has sent; null while the upload is not configured. Any thread. */
    public static @Nullable FindingsLog findings() {
        return findings;
    }

    /** The current settings: {@link CloudSettings#OFF} while the upload is not configured. Any thread. */
    public static CloudSettings settings() {
        return CONNECTIONS.settings();
    }

    private static void finding(IntakeWire.Event event) {
        FindingsLog log = findings;
        if (log == null) return;
        CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(event.player());
        log.add(
                event.player(),
                player == null ? null : player.getName(),
                new Finding(
                        System.currentTimeMillis(),
                        event.checkId(),
                        event.category(),
                        event.confidence(),
                        event.detail(),
                        event.tick()));
        if (player != null) punish(player, event);
    }

    /** Hands {@code event} to the player's {@link Cloud} check on its packet thread, where checks flag. */
    private static void punish(CultPlayer player, IntakeWire.Event event) {
        Cloud cloud = player.checkManager.getCheck(Cloud.class);
        if (cloud == null) return;
        Runnable flag = () -> cloud.finding(event.checkId(), event.confidence(), event.detail());
        var owner = player.user.getPacketExecutor();
        if (owner.inEventLoop()) {
            flag.run();
            return;
        }
        try {
            owner.execute(flag);
        } catch (RejectedExecutionException closed) {
            // The player's connection is gone; nothing is left to punish.
        }
    }

    private static boolean tracked(UUID player) {
        return CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player) != null;
    }

    /** Plugin disable: every tap leaves its pipeline and every socket closes. Does not block. */
    static void shutdown() {
        for (CloudCapture capture : INSTALLED) capture.detach();
        CONNECTIONS.closeAll();
        UPLINKS.clear();
    }

    /**
     * A newer runtime generation took over while this one drains its players. The intake keeps one socket per server
     * id, so this one's sockets send what is queued and close, and its captures move to sockets under ids ending in
     * {@code drainSuffix}, which the newer one never uses. A player streams from one generation at a time: a hand-off
     * releases the capture here before the newer one installs its own. Does not block.
     */
    static void superseded(String drainSuffix) {
        UPLINKS.drain(drainSuffix);
        reconfigureCaptures();
    }

    /** The sockets, held frames and counters, one line each. */
    static List<String> status() {
        List<String> lines = new ArrayList<>();
        CloudSettings settings = CONNECTIONS.settings();
        lines.add(
                CONNECTIONS.available()
                        ? "Cloud upload on" + UPLINKS.draining() + ", protocols " + settings.protocols() + ", "
                                + INSTALLED.size() + " captures"
                        : "Cloud upload off");
        lines.addAll(CONNECTIONS.summaries());
        lines.addAll(CONNECTIONS.diagnosticsSummaries());
        lines.add(CloudCapture.summary());
        lines.add(PlayerText.summary());
        lines.add("Sections: " + Sections.tooLarge.get() + " too large for a frame, " + Sections.startedOver.get()
                + " starts over past " + Sections.MAX_GONE + " owed gones");
        return lines;
    }

    static void installed(CloudCapture capture) {
        INSTALLED.add(capture);
    }

    static void uninstalled(CloudCapture capture) {
        INSTALLED.remove(capture);
    }

    /**
     * Segments' sockets by backend name: the name becomes a server id through {@code cloud.server-id}, and a socket
     * is released once its last segment has left it.
     */
    static final class ConnectionUplinks implements CloudCapture.Uplinks {
        private final CloudConnections connections;
        private final Map<String, Integer> users = new HashMap<>();
        /** Appended to every server id once a newer runtime took over; empty until then. */
        private volatile String drainSuffix = "";

        ConnectionUplinks(CloudConnections connections) {
            this.connections = connections;
        }

        /**
         * Server ids end in {@code suffix} from here, and the sockets under the old ids retire. Both happen under the
         * lock {@link #open} resolves ids in, so an open in flight cannot recreate an old id's socket after it retired.
         */
        void drain(String suffix) {
            synchronized (users) {
                drainSuffix = suffix;
                connections.retireOpen();
            }
        }

        /** The status line's note on a draining generation; empty otherwise. */
        String draining() {
            String suffix = drainSuffix;
            return suffix.isEmpty() ? "" : " for draining players under server ids ending " + suffix;
        }

        @Override
        public boolean enabled() {
            return connections.available();
        }

        @Override
        public @Nullable CloudCapture.Uplink open(String serverName) {
            CloudSettings settings = connections.settings();
            if (!connections.available()) return null;
            String id = CloudServerIds.resolve(settings.serverIdTemplate(), settings.proxyId(), serverName);
            if (id == null) return null;
            synchronized (users) {
                String suffixed = CloudServerIds.withSuffix(id, drainSuffix);
                CloudConnection connection = connections.forServer(suffixed);
                if (connection == null) return null;
                users.merge(suffixed, 1, Integer::sum);
                return new ConnectionUplink(suffixed, connection);
            }
        }

        @Override
        public void release(CloudCapture.Uplink uplink) {
            String id = uplink.serverId();
            synchronized (users) {
                Integer count = users.get(id);
                if (count == null) return;
                if (count > 1) {
                    users.put(id, count - 1);
                    return;
                }
                users.remove(id);
                connections.release(id);
            }
        }

        void clear() {
            synchronized (users) {
                users.clear();
            }
        }
    }

    record ConnectionUplink(String serverId, CloudConnection connection) implements CloudCapture.Uplink {
        @Override
        public boolean same(CloudCapture.Uplink other) {
            return other instanceof ConnectionUplink uplink && uplink.connection == connection;
        }

        @Override
        public void register(UUID player, CloudConnection.Stream stream) {
            connection.register(player, stream);
        }

        @Override
        public void unregister(UUID player, CloudConnection.Stream stream) {
            connection.unregister(player, stream);
        }

        @Override
        public boolean send(long generation, byte[] record, long resolvedExtra) {
            return connection.send(generation, record, resolvedExtra);
        }

        @Override
        public CloudConnection.Terms terms(long generation) {
            return connection.terms(generation);
        }

        @Override
        public boolean textStripped(long generation) {
            return connection.textStripped(generation);
        }

        @Override
        public int maxFrameBytes() {
            return connection.maxFrameBytes();
        }

        @Override
        public boolean backlogged() {
            return connection.backlogged();
        }

        @Override
        public Diagnostics diagnostics() {
            return connection.diagnostics();
        }
    }
}
