package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.routing.NetSettings;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.reload.api.HostContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Dormant mode for the connections this generation owns. A player is checked while the network's shared
 * {@code toggle} is on for the backend server they are on, and dormant while it is off: CultAC then holds no player,
 * decoder or encoder for them, only a {@link DormantWatch}. A player goes dormant as soon as the setting turns off for
 * them, and at a reconfiguration acknowledgement on the way to a server where it is off; a dormant player is checked
 * again at an acknowledgement on the way to a server where it is on, or when they join again.
 */
public final class DormancyController {
    /** How many times in quick succession a detached connection tries to swap its handlers before trying once a second. */
    public static final int SWAP_ATTEMPTS = 20;
    /** The pause before each try that follows the first {@link #SWAP_ATTEMPTS}. */
    static final long SLOW_SWAP_MILLIS = 1_000;

    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile @Nullable Platform platform;
    private static final DormantWatch.Hooks HOOKS = new DormantWatch.Hooks() {
        @Override
        public boolean acknowledged(DormantWatch watch) throws Exception {
            return DormancyController.acknowledged(watch);
        }

        @Override
        public void closed(DormantWatch watch) {
            Platform hooks = platform;
            if (hooks != null) hooks.closed(watch);
        }
    };

    /** The platform side of dormant mode. */
    public interface Platform {
        /** The player of a connection that can go dormant; null for any other, such as a Bedrock one. Any thread. */
        @Nullable
        UUID player(CultConnection connection);

        /** The backend server whose settings {@code player} follows; null when it is not known. Any thread. */
        @Nullable
        String backend(UUID player);

        /**
         * At a reconfiguration acknowledgement, the backend server {@code player} is switching to, else the one they
         * are on. On the packet owner.
         */
        @Nullable
        String target(UUID player);

        /** The name of the handler whose place a watch takes. */
        String decoder();

        /**
         * A watch, with {@link #hooks()} and no retired pings, for a detached connection whose serverbound stream is in
         * {@code play}.
         */
        DormantWatch watch(CultConnection connection, boolean play);

        /** Removes CultAC's handlers from {@code connection}, inside its removal. */
        void removeHandlers(CultConnection connection);

        /** {@code player} went dormant. */
        void detached(UUID player);

        /** Whether this generation attaches connections. */
        boolean accepting();

        /** Attaches a watched connection again, its watch removed, before its acknowledgement is forwarded. */
        void resume(DormantWatch watch) throws Exception;

        /** A watched connection closed. */
        void closed(DormantWatch watch);
    }

    private DormancyController() {}

    /** Set by the platform before the engine starts; null for a platform without dormant mode. */
    public static void platform(@Nullable Platform hooks) {
        platform = hooks;
    }

    /** What every watch of this generation reports to. */
    public static DormantWatch.Hooks hooks() {
        return HOOKS;
    }

    /** Starts following the shared setting; the first call of a generation counts. */
    public static void start() {
        if (!started.compareAndSet(false, true)) return;
        NetworkModule.onSettingsChanged(change -> {
            if (change.affects(NetSettings.TOGGLE)) settle();
        });
        NetworkModule.onPlayerChanged(DormancyController::playerChanged);
    }

    /**
     * Whether a player on {@code backend} is dormant; never while their backend is not known, as a player CultAC stops
     * checking is checked again only at a reconfiguration. Any thread.
     */
    public static boolean dormant(@Nullable String backend) {
        return backend != null
                && !NetworkModule.setting(NetSettings.TOGGLE, backend).on();
    }

    /**
     * Takes the inspected connections whose players are dormant now out of inspection, each on its packet owner;
     * completes once each of those tasks ran. Any thread.
     */
    public static CompletableFuture<Void> settle() {
        Platform hooks = platform;
        if (hooks == null) return CompletableFuture.completedFuture(null);
        List<CompletableFuture<Void>> detaching = new ArrayList<>();
        for (CultConnection connection : CultAPI.INSTANCE.getNetworkManager().connections()) {
            UUID player = connection.dormant() == null ? hooks.player(connection) : null;
            if (player != null && dormant(hooks.backend(player))) detaching.add(detachLater(connection));
        }
        return CompletableFuture.allOf(detaching.toArray(CompletableFuture[]::new));
    }

    /** {@code player} joined or switched backend server: detached when they are dormant there. Any thread. */
    public static void playerChanged(UUID player) {
        Platform hooks = platform;
        User user = hooks == null ? null : CultAPI.INSTANCE.getNetworkManager().getUser(player);
        if (user == null) return;
        CultConnection connection = user.getCultConnection();
        if (connection.dormant() == null && player.equals(hooks.player(connection)) && dormant(hooks.backend(player)))
            detachLater(connection);
    }

    /**
     * How many players this generation holds dormant, of all or, given {@code backends}, of those on one of them.
     */
    public static int dormantPlayers(@Nullable Collection<String> backends) {
        Platform hooks = platform;
        if (hooks == null) return 0;
        List<UUID> players = new ArrayList<>();
        for (DormantWatch watch : DormantWatch.all()) players.add(watch.player());
        for (CultConnection connection : CultAPI.INSTANCE.getNetworkManager().connections()) {
            User user = connection.user();
            if (connection.dormant() != null && user != null && user.getUUID() != null) players.add(user.getUUID());
        }
        if (backends == null) return players.size();
        Set<String> on = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        on.addAll(backends);
        int count = 0;
        for (UUID player : players) {
            String backend = hooks.backend(player);
            if (backend != null && on.contains(backend)) count++;
        }
        return count;
    }

    /**
     * At a reconfiguration acknowledgement no newer generation adopted, on the packet owner, before it is forwarded:
     * detaches an inspected player switching to a server where they are dormant, and swaps the handlers of a detached
     * one, attaching it again when it switches to a server where it is checked. A detached connection whose swap fails
     * keeps its handlers and is left to its swap retries.
     */
    static boolean reconfiguring(CultConnection connection) throws Exception {
        Platform hooks = platform;
        UUID player = hooks == null ? null : hooks.player(connection);
        if (player == null) return true;
        boolean dormant = dormant(hooks.target(player));
        if (connection.dormant() == null) {
            if (dormant && detach(connection) != null) {
                hooks.detached(player);
                if (swap(hooks, connection, false) == null) retrySwap(hooks, connection, 1);
            }
            return true;
        }
        DormantWatch watch = swap(hooks, connection, false);
        HostContext host = RuntimeEnvironment.host();
        if (watch != null && !dormant && hooks.accepting() && (host == null || host.current())) resume(hooks, watch);
        return true;
    }

    /** A watched connection's acknowledgement: a newer generation adopts it, or it is attached again, or stays watched. */
    private static boolean acknowledged(DormantWatch watch) throws Exception {
        HostContext host = RuntimeEnvironment.host();
        if (host != null && host.platform().equals("velocity") && host.handoff(watch.channel(), watch.nativePlayer()))
            return true;
        Platform hooks = platform;
        if (hooks == null || (host != null && !host.current()) || !hooks.accepting()) return false;
        if (dormant(hooks.target(watch.player()))) return false;
        resume(hooks, watch);
        return true;
    }

    private static void resume(Platform hooks, DormantWatch watch) throws Exception {
        var pipeline = watch.channel().pipeline();
        if (pipeline.context(watch) != null) pipeline.remove(watch);
        hooks.resume(watch);
    }

    /** Detaches the connection on its packet owner; completes once that ran or could not. */
    private static CompletableFuture<Void> detachLater(CultConnection connection) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            connection.executeLater(() -> {
                try {
                    detachNow(connection);
                } finally {
                    done.complete(null);
                }
            });
        } catch (RuntimeException closing) {
            done.complete(null);
        }
        return done.completeOnTimeout(null, 5, TimeUnit.SECONDS);
    }

    private static void detachNow(CultConnection connection) {
        Platform hooks = platform;
        UUID player = hooks == null || connection.dormant() != null ? null : hooks.player(connection);
        if (player == null || CultAPI.INSTANCE.getNetworkManager().connection(connection.channel()) != connection)
            return;
        if (!dormant(hooks.backend(player)) || detach(connection) == null) return;
        hooks.detached(player);
        trySwap(hooks, connection, 0);
    }

    /**
     * Swaps the handlers of a detached connection once its encoder settles, trying again until the swap succeeds or
     * the connection is gone. A detached connection that still holds CultAC's handlers has one such chain of tries.
     */
    private static void trySwap(Platform hooks, CultConnection connection, int attempt) {
        if (CultAPI.INSTANCE.getNetworkManager().connection(connection.channel()) != connection
                || !connection.channel().isActive()) return;
        boolean play = connection.phase(PacketDirection.SERVERBOUND) == ConnectionPhase.PLAY;
        if (swappable(connection) && swap(hooks, connection, play) != null) return;
        retrySwap(hooks, connection, attempt + 1);
    }

    /** Queues swap try {@code attempt}: the first {@link #SWAP_ATTEMPTS} at once, each later one after a pause. */
    private static void retrySwap(Platform hooks, CultConnection connection, int attempt) {
        Runnable retry = () -> trySwap(hooks, connection, attempt);
        if (attempt > SWAP_ATTEMPTS) {
            connection.owner().schedule(() -> connection.execute(retry), SLOW_SWAP_MILLIS, TimeUnit.MILLISECONDS);
            return;
        }
        // A swap inside the encoder's own write would discard what that write emits, so each try is a task of its own.
        if (connection.hasClientTickTasks()) connection.afterClientTick(() -> connection.executeLater(retry));
        else connection.executeLater(retry);
    }

    /**
     * Ends the player's inspection on a connection that stays open, on its packet owner: captures its continuation
     * and whether it completed its login, releases its cloud capture, stops its packet log, detaches its player and
     * closes its session row, then marks it {@link CultConnection#dormant}. Null when it is dormant already or closed.
     */
    public static @Nullable Map<String, Object> detach(CultConnection connection) {
        if (!connection.owner().inEventLoop()) throw new IllegalStateException("Detach requires the packet owner");
        if (connection.dormant() != null || connection.disconnected()) return null;
        Map<String, Object> state = ConnectionContinuation.capture(connection);
        state.put(DormantWatch.LOGIN_NOTIFIED, connection.loginNotified());
        CloudModule.released(connection.player());
        connection.stopPacketLog("dormant");
        User user = connection.user();
        if (user != null) CultAPI.INSTANCE.getPlayerDataManager().detachDormant(user, connection.loginNotified());
        connection.dormant(state);
        return state;
    }

    /** Whether the encoder holds no open flush bundle and no task waits for the client's tick end. On the owner. */
    public static boolean swappable(CultConnection connection) {
        return !connection.flushBundleOpen() && !connection.hasClientTickTasks();
    }

    /**
     * Replaces CultAC's handlers on a detached connection with a watch, on its packet owner, in one task: installs the
     * watch in front of the platform's decoder on the decoder's executor, removes CultAC's handlers, releases the
     * connection and hands the watch the pings retired by then. Null when the swap failed, which it logs: a connection
     * whose watch could not be installed is left as it was; one whose handlers could not all be removed keeps the
     * watch, or is closed where CultAC fails closed.
     */
    private static @Nullable DormantWatch swap(Platform hooks, CultConnection connection, boolean play) {
        var channel = connection.channel();
        var pipeline = channel.pipeline();
        DormantWatch watch;
        try {
            String decoder = hooks.decoder();
            var context = pipeline.context(decoder);
            if (context == null) throw new IllegalStateException("No " + decoder + " to replace: " + pipeline.names());
            watch = hooks.watch(connection, play);
            var executor = context.executor() == channel.eventLoop() ? null : context.executor();
            pipeline.addBefore(executor, decoder, DormantWatch.NAME, watch);
        } catch (RuntimeException failure) {
            CultNetworkManager.reportFailure("dormant handler swap", name(connection), failure);
            return null;
        }
        boolean removed = true;
        try {
            connection
                    .removeHandlers(() -> hooks.removeHandlers(connection))
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            String why = "removing CultAC's handlers from their dormant connection failed";
            if (!CultAPI.INSTANCE.getConfigManager().failsOpen()) {
                LogUtil.error("Disconnecting " + name(connection) + "; " + why, cause);
                if (pipeline.context(watch) != null) pipeline.remove(watch);
                channel.close();
                return null;
            }
            LogUtil.warn("Watching " + name(connection) + " with CultAC handlers left; " + why, cause);
            removed = false;
        }
        CultAPI.INSTANCE.getNetworkManager().release(channel);
        watch.retire(connection.drainRetiredPings());
        return removed ? watch : null;
    }

    private static String name(CultConnection connection) {
        User user = connection.user();
        return user == null ? String.valueOf(connection.channel().remoteAddress()) : user.getName();
    }
}
