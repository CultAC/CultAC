package ac.cult.cultac.manager;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.alerts.AlertKind;
import ac.cult.cultac.manager.alerts.AlertNetwork;
import ac.cult.cultac.manager.alerts.AlertPairs;
import ac.cult.cultac.manager.alerts.AlertScope;
import ac.cult.cultac.manager.alerts.AlertScopes;
import ac.cult.cultac.manager.alerts.AlertSubject;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.network.routing.NetworkService;
import ac.cult.cultac.network.routing.SharedClaims;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.runtime.NativePlayerSet;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.reload.api.HostContext;
import ac.cult.reload.host.AlertSubscriptions;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.alerts.AlertManager;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.config.ConfigReloadable;
import ac.grim.grimac.api.storage.DataStore;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Efficient implementation of AlertManager, handling state changes and notifications.
 * Caches toggle messages for performance.
 */
public final class AlertManagerImpl implements AlertManager, ConfigReloadable, StartableInitable {
    private static @MonotonicNonNull PlatformServer platformServer;

    private final AlertScopes scopes;
    private final AlertPairs pairs;
    private final AlertNetwork network;
    private final BooleanSupplier proxy;
    private final BooleanSupplier relay;
    private final AtomicBoolean networkHooked = new AtomicBoolean();

    /** Who a message reached; {@code pair} is set on a verbose line that also went to the other instances. */
    private static final class Recipients extends HashSet<@Nullable PlatformPlayer> {
        @Nullable
        String pair;
    }

    private enum AlertType {
        NORMAL(AlertKind.ALERT, "%prefix% &fAlerts enabled", "%prefix% &fAlerts disabled"),
        VERBOSE(AlertKind.VERBOSE, "%prefix% &fVerbose enabled", "%prefix% &fVerbose disabled"),
        BRAND(AlertKind.BRAND, "%prefix% &fBrands enabled", "%prefix% &fBrands disabled");

        public final @NotNull AlertKind kind;
        public @NotNull String enableMessage;
        public @NotNull String disableMessage;
        public final @NotNull Set<@NotNull PlatformPlayer> players;
        private final AtomicBoolean console;

        AlertType(@NotNull AlertKind kind, @NotNull String enableMessage, @NotNull String disableMessage) {
            this.kind = kind;
            this.enableMessage = enableMessage;
            this.disableMessage = disableMessage;
            var host = RuntimeEnvironment.host();
            if (host == null) {
                players = new CopyOnWriteArraySet<>();
                console = new AtomicBoolean();
            } else {
                var subscriptions = (AlertSubscriptions) host.shared("alerts", AlertSubscriptions::new);
                players = new NativePlayerSet(subscriptions.players(name()));
                console = subscriptions.console(name());
            }
        }

        @Contract(pure = true)
        public boolean hasListeners() {
            return !players.isEmpty() || console.get();
        }

        @Contract(pure = true)
        public @NotNull String getToggleMessage(boolean enabled) {
            return enabled ? enableMessage : disableMessage;
        }

        static AlertType of(AlertKind kind) {
            return switch (kind) {
                case ALERT -> NORMAL;
                case VERBOSE -> VERBOSE;
                case BRAND -> BRAND;
            };
        }

        /**
         * @param component the message to send to listeners
         * @param excluding the listeners to exclude, null means console
         * @param covered the listeners whose scope covers the message
         * @param toConsole whether the console may get it
         * @return listeners this message was sent to, null means console
         */
        Recipients send(
                Component component,
                @Nullable Set<@Nullable PlatformPlayer> excluding,
                Predicate<PlatformPlayer> covered,
                boolean toConsole) {
            Recipients listeners = new Recipients();
            listeners.addAll(players);
            if (excluding != null) {
                Set<Object> excludedNatives = Collections.newSetFromMap(new IdentityHashMap<>());
                for (PlatformPlayer excluded : excluding) {
                    if (excluded != null) excludedNatives.add(excluded.getNative());
                }
                listeners.removeIf(listener -> excludedNatives.contains(listener.getNative()));
            }
            listeners.removeIf(listener -> !covered.test(listener));

            for (PlatformPlayer platformPlayer : listeners) {
                platformPlayer.sendMessage(component);
            }

            if (toConsole && console.get() && (excluding == null || !excluding.contains(null))) {
                platformServer.getConsoleSender().sendMessage(component);
                listeners.add(null);
            }

            return listeners;
        }
    }

    public AlertManagerImpl() {
        this(
                new AlertScopes(RuntimeEnvironment.host(), AlertManagerImpl::dataStore),
                NetworkModule::service,
                () -> CultAPI.INSTANCE.getPlatform() == Platform.VELOCITY,
                RuntimeEnvironment.host(),
                () -> platformServer != null && platformServer.receivesForwardedAlerts());
    }

    /**
     * @param proxy whether this instance is a proxy, whose players are on backend servers
     * @param host the host every engine generation on this instance shares, if any
     * @param relay whether this server shows alerts other servers forward through the proxy's plugin messaging
     */
    AlertManagerImpl(
            AlertScopes scopes,
            Supplier<@Nullable NetworkService> service,
            BooleanSupplier proxy,
            @Nullable HostContext host,
            BooleanSupplier relay) {
        this.scopes = scopes;
        this.proxy = proxy;
        this.relay = relay;
        this.pairs = new AlertPairs(host, System::currentTimeMillis);
        this.network = new AlertNetwork(
                service,
                new NetworkStaff(),
                new SharedClaims(host, AlertNetwork.CLAIMS, System::currentTimeMillis),
                System::currentTimeMillis);
    }

    private final class NetworkStaff implements AlertNetwork.Staff {
        @Override
        public void received(AlertKind kind, Component component, @Nullable String server, @Nullable String pair) {
            deliverReceived(kind, component, server, pair);
        }

        @Override
        public boolean wantGlobalVerbose() {
            for (PlatformPlayer staff : AlertType.VERBOSE.players) {
                if (scopes.scope(staff) == AlertScope.GLOBAL) return true;
            }
            return false;
        }
    }

    private static @Nullable DataStore dataStore() {
        DataStoreLifecycle lifecycle = CultAPI.INSTANCE.getDataStoreLifecycle();
        return lifecycle == null ? null : lifecycle.dataStore();
    }

    @Override
    public void start() {
        platformServer = CultAPI.INSTANCE.getPlatformServer();
        reload(CultAPI.INSTANCE.getConfigManager().getConfig());
        if (networkHooked.compareAndSet(false, true)) NetworkModule.onStart(network::attach);
    }

    AlertNetwork network() {
        return network;
    }

    @Override
    public void reload(ConfigManager config) {
        var host = RuntimeEnvironment.host();
        if (host == null || host.current()) {
            setConsoleAlertsEnabled(config.getBooleanElse("alerts.print-to-console", true), true);
            setConsoleVerboseEnabled(config.getBooleanElse("verbose.print-to-console", false), true);
        }

        AlertType.NORMAL.enableMessage = config.getStringElse("alerts-enabled", "%prefix% &fAlerts enabled");
        AlertType.NORMAL.disableMessage = config.getStringElse("alerts-disabled", "%prefix% &fAlerts disabled");
        AlertType.VERBOSE.enableMessage = config.getStringElse("verbose-enabled", "%prefix% &fVerbose enabled");
        AlertType.VERBOSE.disableMessage = config.getStringElse("verbose-disabled", "%prefix% &fVerbose disabled");
        AlertType.BRAND.enableMessage = config.getStringElse("brands-enabled", "%prefix% &fBrands enabled");
        AlertType.BRAND.disableMessage = config.getStringElse("brands-disabled", "%prefix% &fBrands disabled");
    }

    /**
     * Gets the non-null PlatformPlayer from a GrimUser.
     * @throws IllegalArgumentException if the user is not a CultPlayer.
     * @throws NullPointerException if the CultPlayer's platformPlayer is null.
     */
    private @NotNull PlatformPlayer requirePlatformPlayerFromUser(@NotNull GrimUser user) {
        Objects.requireNonNull(
                user, "user cannot be null"); // Should be guaranteed by interface contract, but good practice

        if (!(user instanceof CultPlayer cultPlayer)) {
            // Throw a specific exception if the type is wrong
            throw new IllegalArgumentException(
                    "AlertManager action called with non-CultPlayer user: " + user.getName());
        }

        PlatformPlayer platformPlayer = cultPlayer.platformPlayer;

        // Throw NullPointerException with the specific message if platformPlayer is null
        Objects.requireNonNull(
                platformPlayer,
                "AlertManager action for user " + user.getName()
                        + " with null platformPlayer (potentially during early join)");

        return platformPlayer;
    }

    /** Gets the cached message, applies placeholders, and sends it to a PlatformPlayer. */
    private static void sendToggleMessage(@NotNull PlatformPlayer player, boolean enabled, @NotNull AlertType type) {
        String rawMessage = type.getToggleMessage(enabled);
        if (rawMessage.isEmpty()) return;

        String messageWithPlaceholders = MessageUtil.replacePlaceholders(player, rawMessage);
        player.sendMessage(MessageUtil.miniMessage(messageWithPlaceholders));
    }

    @Override
    public boolean hasAlertsEnabled(@NotNull GrimUser player) {
        return hasAlertsEnabled(requirePlatformPlayerFromUser(player));
    }

    @Override
    public void setAlertsEnabled(@NotNull GrimUser player, boolean enabled, boolean silent) {
        setAlertsEnabled(requirePlatformPlayerFromUser(player), enabled, silent);
    }

    @Override
    public boolean hasVerboseEnabled(@NotNull GrimUser player) {
        return hasVerboseEnabled(requirePlatformPlayerFromUser(player));
    }

    @Override
    public void setVerboseEnabled(@NotNull GrimUser player, boolean enabled, boolean silent) {
        setVerboseEnabled(requirePlatformPlayerFromUser(player), enabled, silent);
    }

    @Override
    public boolean hasBrandsEnabled(@NotNull GrimUser player) {
        CultPlayer cultPlayer = (CultPlayer) player;
        // Some proxies break packet order in sending brand and send the data too early for performance
        // which causes us to iterate over all players with this method
        // before platformPlayer is intialized; while generally packet order is important to maintain
        // for compatibility's sake lets just default to not sending alerts to these players
        if (cultPlayer.platformPlayer == null) return false;

        return hasBrandsEnabled(cultPlayer.platformPlayer);
    }

    @Override
    public void setBrandsEnabled(@NotNull GrimUser player, boolean enabled, boolean silent) {
        setPlayerStateAndNotify(requirePlatformPlayerFromUser(player), enabled, silent, AlertType.BRAND);
    }

    @Deprecated
    @Override
    public boolean hasAlertsEnabled(Player player) {
        if (player == null) return false;
        return hasAlertsEnabled(CultAPI.INSTANCE.getPlatformPlayerFactory().getFromNativePlayerType(player));
    }

    @Deprecated
    @Override
    public void toggleAlerts(Player player) {
        if (player == null) return;
        toggleAlerts(CultAPI.INSTANCE.getPlatformPlayerFactory().getFromNativePlayerType(player));
    }

    @Deprecated
    @Override
    public boolean hasVerboseEnabled(Player player) {
        if (player == null) return false;
        return hasVerboseEnabled(CultAPI.INSTANCE.getPlatformPlayerFactory().getFromNativePlayerType(player));
    }

    @Deprecated
    @Override
    public void toggleVerbose(Player player) {
        if (player == null) return;
        toggleVerbose(CultAPI.INSTANCE.getPlatformPlayerFactory().getFromNativePlayerType(player));
    }

    public void handlePlayerQuit(@Nullable PlatformPlayer platformPlayer) {
        if (platformPlayer == null) return;

        AlertType.NORMAL.players.remove(platformPlayer);
        boolean verbose = AlertType.VERBOSE.players.remove(platformPlayer);
        AlertType.BRAND.players.remove(platformPlayer);
        scopes.forget(platformPlayer);
        if (verbose) network.demandChanged();
    }

    /** Starts loading {@code player}'s persisted alert scope; the permission default applies until it lands. */
    public void loadScope(@NotNull PlatformPlayer player) {
        scopes.load(player, network::demandChanged);
    }

    /** {@code player}'s alert scope as it applies now: {@code server} for everyone unless {@link #networkAlerts}. */
    public @NotNull AlertScope scope(@NotNull PlatformPlayer player) {
        return networkAlerts() ? scopes.scope(player) : AlertScope.SERVER;
    }

    /** The scope {@code player} chose; null when the permission default applies. */
    public @Nullable AlertScope chosenScope(@NotNull PlatformPlayer player) {
        return scopes.chosen(player);
    }

    /** Sets and persists {@code player}'s alert scope. */
    public void chooseScope(@NotNull PlatformPlayer player, @NotNull AlertScope scope) {
        scopes.choose(player, scope);
        if (hasVerboseEnabled(player)) network.demandChanged();
    }

    /** Whether other instances' alerts reach this one: over a network transport, or the proxy's plugin messaging. */
    public boolean networkAlerts() {
        return network.running() || relay.getAsBoolean();
    }

    public boolean toggleConsoleAlerts() {
        return toggleConsoleAlerts(false);
    }

    public boolean toggleConsoleAlerts(boolean silent) {
        return setConsoleAlertsEnabled(!hasConsoleAlertsEnabled(), silent);
    }

    @Contract("_, _ -> param1")
    public boolean setConsoleAlertsEnabled(boolean enabled, boolean silent) {
        setConsoleStateAndNotify(AlertType.NORMAL, enabled, silent);
        if (!enabled) setConsoleVerboseEnabled(false, silent);
        return enabled;
    }

    @Contract(pure = true)
    public boolean hasConsoleAlertsEnabled() {
        return AlertType.NORMAL.console.get();
    }

    public boolean toggleConsoleVerbose() {
        return toggleConsoleVerbose(false);
    }

    public boolean toggleConsoleVerbose(boolean silent) {
        return setConsoleVerboseEnabled(!hasConsoleVerboseEnabled(), silent);
    }

    @Contract("_, _ -> param1")
    public boolean setConsoleVerboseEnabled(boolean enabled, boolean silent) {
        if (enabled) setConsoleAlertsEnabled(true, silent);
        return setConsoleStateAndNotify(AlertType.VERBOSE, enabled, silent);
    }

    @Contract(pure = true)
    public boolean hasConsoleVerboseEnabled() {
        return AlertType.VERBOSE.console.get();
    }

    public boolean toggleConsoleBrands() {
        return toggleConsoleBrands(false);
    }

    public boolean toggleConsoleBrands(boolean silent) {
        return setConsoleBrandsEnabled(!hasConsoleBrandsEnabled(), silent);
    }

    @Contract("_, _ -> param1")
    public boolean setConsoleBrandsEnabled(boolean enabled, boolean silent) {
        return setConsoleStateAndNotify(AlertType.BRAND, enabled, silent);
    }

    @Contract(pure = true)
    public boolean hasConsoleBrandsEnabled() {
        return AlertType.BRAND.console.get();
    }

    @Contract("_, _, _ -> param2")
    private boolean setConsoleStateAndNotify(@NotNull AlertType type, boolean enabled, boolean silent) {
        if (type.console.get() != enabled && !silent) {
            String rawMessage = type.getToggleMessage(enabled);
            if (!rawMessage.isEmpty()) {
                platformServer
                        .getConsoleSender()
                        .sendMessage(MessageUtil.miniMessage(
                                MessageUtil.replacePlaceholders((PlatformPlayer) null, rawMessage)));
            }
        }

        type.console.set(enabled);
        return enabled;
    }

    private void setPlayerStateAndNotify(
            @NotNull PlatformPlayer platformPlayer, boolean enabled, boolean silent, @NotNull AlertType type) {
        Objects.requireNonNull(platformPlayer, "platformPlayer cannot be null");
        boolean changed = enabled ? type.players.add(platformPlayer) : type.players.remove(platformPlayer);

        if (changed && !silent) {
            sendToggleMessage(platformPlayer, enabled, type);
        }
        if (changed && type == AlertType.VERBOSE) network.demandChanged();
    }

    public boolean toggleBrands(@NotNull PlatformPlayer player, boolean silent) {
        return setBrandsEnabled(player, !hasBrandsEnabled(player), silent);
    }

    @Contract("_, _, _ -> param2")
    public boolean setBrandsEnabled(@NotNull PlatformPlayer player, boolean enabled, boolean silent) {
        setPlayerStateAndNotify(player, enabled, silent, AlertType.BRAND);
        return enabled;
    }

    @Contract(pure = true)
    public boolean hasBrandsEnabled(@NotNull PlatformPlayer player) {
        return AlertType.BRAND.players.contains(player);
    }

    public boolean toggleVerbose(@NotNull PlatformPlayer player) {
        return toggleVerbose(player, false);
    }

    public boolean toggleVerbose(@NotNull PlatformPlayer player, boolean silent) {
        return setVerboseEnabled(player, !hasVerboseEnabled(player), silent);
    }

    @Contract("_, _, _ -> param2")
    public boolean setVerboseEnabled(@NotNull PlatformPlayer player, boolean enabled, boolean silent) {
        if (enabled) setAlertsEnabled(player, true, silent);
        setPlayerStateAndNotify(player, enabled, silent, AlertType.VERBOSE);
        return enabled;
    }

    @Contract(pure = true)
    public boolean hasVerboseEnabled(@NotNull PlatformPlayer player) {
        return AlertType.VERBOSE.players.contains(player);
    }

    public boolean toggleAlerts(@NotNull PlatformPlayer player) {
        return toggleAlerts(player, false);
    }

    public boolean toggleAlerts(@NotNull PlatformPlayer player, boolean silent) {
        return setAlertsEnabled(player, !hasAlertsEnabled(player), silent);
    }

    @Contract("_, _, _ -> param2")
    public boolean setAlertsEnabled(@NotNull PlatformPlayer player, boolean enabled, boolean silent) {
        setPlayerStateAndNotify(player, enabled, silent, AlertType.NORMAL);
        if (!enabled) setVerboseEnabled(player, false, silent);
        return enabled;
    }

    @Contract(pure = true)
    public boolean hasAlertsEnabled(@NotNull PlatformPlayer player) {
        return AlertType.NORMAL.players.contains(player);
    }

    /**
     * A brand message about no player: every subscriber here, whatever their scope, and global-scope staff elsewhere.
     *
     * @param component the message to send to listeners
     * @param excluding the listeners to exclude, null means console
     * @return listeners this message was sent to, null means console
     */
    public Set<PlatformPlayer> sendBrand(Component component, @Nullable Set<@Nullable PlatformPlayer> excluding) {
        return sendBrand(AlertSubject.NONE, component, excluding);
    }

    /**
     * Sends a brand message about {@code subject} to the staff whose scope covers the subject's server and to every
     * other instance.
     *
     * @param component the message to send to listeners
     * @param excluding the listeners to exclude, null means console
     * @return listeners this message was sent to, null means console
     */
    public Set<PlatformPlayer> sendBrand(
            @NotNull CultPlayer subject, Component component, @Nullable Set<@Nullable PlatformPlayer> excluding) {
        return sendBrand(AlertSubject.of(subject), component, excluding);
    }

    Set<PlatformPlayer> sendBrand(
            AlertSubject subject, Component component, @Nullable Set<@Nullable PlatformPlayer> excluding) {
        return raise(AlertType.BRAND, subject, component, excluding);
    }

    /**
     * A verbose line about no player: every subscriber here, whatever their scope, and global-scope staff elsewhere.
     *
     * @param component the message to send to listeners
     * @param excluding the listeners to exclude, null means console
     * @return listeners this message was sent to, null means console
     */
    public Set<PlatformPlayer> sendVerbose(Component component, @Nullable Set<@Nullable PlatformPlayer> excluding) {
        return sendVerbose(AlertSubject.NONE, component, excluding);
    }

    /**
     * Sends a verbose line about {@code subject} to the staff whose scope covers the subject's server, and to the other
     * instances while one wants verbose. Passed to {@link #sendAlert(CultPlayer, Component, Set)} as
     * {@code excluding}, the returned set keeps the alert from everyone who got this verbose copy, here or elsewhere.
     *
     * @param component the message to send to listeners
     * @param excluding the listeners to exclude, null means console
     * @return listeners this message was sent to, null means console
     */
    public Set<PlatformPlayer> sendVerbose(
            @NotNull CultPlayer subject, Component component, @Nullable Set<@Nullable PlatformPlayer> excluding) {
        return sendVerbose(AlertSubject.of(subject), component, excluding);
    }

    Set<PlatformPlayer> sendVerbose(
            AlertSubject subject, Component component, @Nullable Set<@Nullable PlatformPlayer> excluding) {
        return raise(AlertType.VERBOSE, subject, component, excluding);
    }

    /**
     * An alert about no player: every subscriber here, whatever their scope, and global-scope staff elsewhere.
     *
     * @param component the message to send to listeners
     * @param excluding the listeners to exclude, null means console
     * @return listeners this message was sent to, null means console
     */
    public Set<PlatformPlayer> sendAlert(Component component, @Nullable Set<@Nullable PlatformPlayer> excluding) {
        return sendAlert(AlertSubject.NONE, component, excluding);
    }

    /**
     * Sends an alert about {@code subject} to the staff whose scope covers the subject's server and to every other
     * instance.
     *
     * @param component the message to send to listeners
     * @param excluding the listeners to exclude, null means console
     * @return listeners this message was sent to, null means console
     */
    public Set<PlatformPlayer> sendAlert(
            @NotNull CultPlayer subject, Component component, @Nullable Set<@Nullable PlatformPlayer> excluding) {
        return sendAlert(AlertSubject.of(subject), component, excluding);
    }

    Set<PlatformPlayer> sendAlert(
            AlertSubject subject, Component component, @Nullable Set<@Nullable PlatformPlayer> excluding) {
        return raise(AlertType.NORMAL, subject, component, excluding);
    }

    /**
     * Shows an alert another server forwarded through the proxy's plugin messaging as one received about an unknown
     * server: global-scope staff only, never the console, and not broadcast again.
     */
    public void relayAlert(Component component) {
        deliverReceived(AlertKind.ALERT, component, null, null);
    }

    private Set<PlatformPlayer> raise(
            AlertType type,
            AlertSubject subject,
            Component component,
            @Nullable Set<@Nullable PlatformPlayer> excluding) {
        Predicate<PlatformPlayer> covered =
                subject.none() ? staff -> true : staff -> covers(staff, subject.server(), true);
        Recipients recipients = type.send(component, excluding, covered, true);
        String pair = switch (type) {
            case VERBOSE -> AlertNetwork.newPair();
            case NORMAL -> excluding instanceof Recipients verbose ? verbose.pair : null;
            case BRAND -> null;
        };
        AlertSubject wire = proxy.getAsBoolean() || subject.none() ? subject : subject.on(network.instanceId());
        if (network.publish(type.kind, wire, component, pair) && type == AlertType.VERBOSE) recipients.pair = pair;
        return recipients;
    }

    /**
     * Shows staff here an alert another instance raised; the console never gets one. Of a verbose line and the alert
     * sharing its {@code pair}, each staff member gets the first that reaches them.
     */
    void deliverReceived(AlertKind kind, Component component, @Nullable String server, @Nullable String pair) {
        Predicate<PlatformPlayer> covered = staff -> covers(staff, server, false);
        if (pair != null) covered = covered.and(staff -> pairs.take(pair, staff.getNative()));
        AlertType.of(kind).send(component, null, covered, false);
    }

    private boolean covers(PlatformPlayer staff, @Nullable String server, boolean local) {
        return scope(staff).covers(staff.serverName(), server, local, proxy.getAsBoolean());
    }

    /** Whether verbose lines are wanted here or, while a transport runs, by another instance's staff. */
    public boolean hasVerboseListeners() {
        return AlertType.VERBOSE.hasListeners() || network.verboseWanted();
    }

    @Contract(pure = true)
    public boolean hasAlertListeners() {
        return AlertType.NORMAL.hasListeners();
    }
}
