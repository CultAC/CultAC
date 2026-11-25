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
import io.netty.channel.ChannelPipeline;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Dormant mode for the connections this generation owns. A player is checked while the network's shared
 * {@code toggle} is on for the backend server they are on, and dormant while it is off: CultAC then holds no player,
 * decoder or encoder for them, only a {@link DormantWatch}, or, on a connection that does not {@link Platform#swaps
 * swap}, CultAC's handlers without a player. A player goes dormant as soon as the setting turns off for
 * them, at their join while the instance's value is every player's, else once their connection to a server where it is
 * off completed, and at a reconfiguration acknowledgement on the way to such a server; a dormant player is checked again
 * at an acknowledgement on the way to a server where it is on, or when they join again, and a player dormant at their
 * join once it turns on before their login completed. A connection never keeps a watch next to CultAC's handlers.
 */
public final class DormancyController {
    /** How many times in quick succession a detached connection tries to swap its handlers before trying once a second. */
    public static final int SWAP_ATTEMPTS = 20;
    /** The pause before each try that follows the first {@link #SWAP_ATTEMPTS}. */
    static final long SLOW_SWAP_MILLIS = 1_000;
    /** Mark key: the connection was marked dormant at its join. */
    private static final String JOINED = "joined-dormant";

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

        @Override
        public void drained(DormantWatch watch) {
            Platform hooks = platform;
            try {
                if (hooks != null) hooks.drained(watch);
            } finally {
                RuntimeEnvironment.release(watch.channel(), CompletableFuture.completedFuture(null));
            }
        }
    };

    /** The platform side of dormant mode. */
    public interface Platform {
        /** The player of a connection that can go dormant; null for any other. Any thread. */
        @Nullable
        UUID player(CultConnection connection);

        /**
         * Whether a detached {@code connection} swaps CultAC's handlers for a watch; one that does not keeps them, and
         * its player is checked again only when they join again.
         */
        default boolean swaps(CultConnection connection) {
            return true;
        }

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

        /** {@code player}, whose connection was marked dormant at their join, is inspected from their login on. */
        default void awake(UUID player) {}

        /** Whether this generation attaches connections. */
        boolean accepting();

        /** Attaches a watched connection again, its watch removed, before its acknowledgement is forwarded. */
        void resume(DormantWatch watch) throws Exception;

        /** A watched connection closed. */
        void closed(DormantWatch watch);

        /**
         * A draining watch answered its last retired ping and left its open connection, whose engine lease is released
         * after this returns.
         */
        default void drained(DormantWatch watch) {}

        /** The dormant players this generation holds neither a watch nor a connection of. Any thread. */
        default Collection<UUID> unwatched() {
            return List.of();
        }
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
     * Whether a player on {@code backend} is dormant. A player whose backend is not known follows the instance's value
     * while no backend server's {@code toggle} record can decide for them, and is checked while one can, as a player
     * CultAC stops checking is checked again only at a reconfiguration. Any thread.
     */
    public static boolean dormant(@Nullable String backend) {
        NetSettings.Value value = NetworkModule.playerSetting(NetSettings.TOGGLE, backend);
        return value != null && !value.on();
    }

    /**
     * Marks a connection opening while its player is dormant, before its login: it keeps CultAC's handlers and gets no
     * CultAC player, unless the setting turns on for its player before its login completes.
     */
    public static void joinedDormant(CultConnection connection) {
        connection.dormant(Map.of(DormantWatch.LOGIN_NOTIFIED, false, JOINED, true));
    }

    /**
     * Takes the mark off a connection marked dormant at its join whose login has not completed, when its player is not
     * dormant; true when it did. On the packet owner.
     */
    public static boolean wake(CultConnection connection) {
        Platform hooks = platform;
        if (hooks == null || !joinedBeforeLogin(connection)) return false;
        UUID player = hooks.player(connection);
        if (player == null || dormant(hooks.target(player))) return false;
        connection.awake();
        hooks.awake(player);
        return true;
    }

    /**
     * Takes the inspected connections whose players are dormant out of inspection, and inspects the connections marked
     * dormant at their join, before their login completed, whose players are not, each on its packet owner; completes
     * once each of those tasks ran. Any thread.
     */
    public static CompletableFuture<Void> settle() {
        Platform hooks = platform;
        if (hooks == null) return CompletableFuture.completedFuture(null);
        List<CompletableFuture<Void>> settling = new ArrayList<>();
        for (CultConnection connection : CultAPI.INSTANCE.getNetworkManager().connections()) {
            if (joinedBeforeLogin(connection)) {
                settling.add(onOwner(connection, () -> wakeNow(connection)));
                continue;
            }
            UUID player = connection.dormant() == null ? hooks.player(connection) : null;
            if (player != null && dormant(hooks.backend(player))) settling.add(detachLater(connection));
        }
        return CompletableFuture.allOf(settling.toArray(CompletableFuture[]::new));
    }

    /** Whether the connection carries the dormant mark of its join and has not completed its login. */
    private static boolean joinedBeforeLogin(CultConnection connection) {
        Map<String, Object> mark = connection.dormant();
        return mark != null && Boolean.TRUE.equals(mark.get(JOINED)) && !connection.loginNotified();
    }

    /** Creates the player of a connection marked dormant at its join once it {@link #wake wakes}. On the owner. */
    private static void wakeNow(CultConnection connection) {
        User user = connection.user();
        if (user == null
                || connection.excluded()
                || CultAPI.INSTANCE.getNetworkManager().connection(connection.channel()) != connection
                || !wake(connection)) return;
        CultAPI.INSTANCE.getPlayerDataManager().addUser(user);
        PacketConnections.observePlatformBrand(connection);
    }

    /**
     * {@code player} joined, or their connection to a backend server completed: detached when they are dormant there.
     * Any thread.
     */
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
        Set<UUID> players = new HashSet<>();
        for (DormantWatch watch : DormantWatch.all()) players.add(watch.player());
        for (CultConnection connection : CultAPI.INSTANCE.getNetworkManager().connections()) {
            User user = connection.user();
            if (connection.dormant() != null && user != null && user.getUUID() != null) players.add(user.getUUID());
        }
        players.addAll(hooks.unwatched());
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
     * keeps its handlers and is left to its swap retries, unless it switches to a server where it is checked and CultAC
     * fails closed: that one is closed. A connection that does not {@link Platform#swaps swap} is only detached.
     */
    static boolean reconfiguring(CultConnection connection) throws Exception {
        Platform hooks = platform;
        UUID player = hooks == null ? null : hooks.player(connection);
        if (player == null) return true;
        boolean dormant = dormant(hooks.target(player));
        if (!hooks.swaps(connection)) {
            if (dormant && detach(connection) != null) hooks.detached(player);
            return true;
        }
        if (connection.dormant() == null) {
            if (dormant && detach(connection) != null) {
                hooks.detached(player);
                if (swap(hooks, connection, false) == null) {
                    if (!connection.channel().isActive()) return false;
                    retrySwap(hooks, connection, 1);
                }
            }
            return true;
        }
        DormantWatch watch = swap(hooks, connection, false);
        if (watch == null) {
            if (!connection.channel().isActive()) return false;
            if (dormant) return true;
            String why =
                    "their dormant connection could not take a watch at a switch to a server where they are checked";
            if (!CultAPI.INSTANCE.getConfigManager().failsOpen()) {
                LogUtil.error("Disconnecting " + name(connection) + "; " + why);
                connection.channel().close();
                return false;
            }
            LogUtil.warn("Not checking " + name(connection) + " until their next switch; " + why);
            return true;
        }
        HostContext host = RuntimeEnvironment.host();
        if (!dormant && hooks.accepting() && (host == null || host.current())) resume(hooks, watch);
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

    /** Attaches a watched connection again; closes it, logged, while CultAC's decoder is still on it. */
    private static void resume(Platform hooks, DormantWatch watch) throws Exception {
        var pipeline = watch.channel().pipeline();
        if (pipeline.get(hooks.decoder()) != null) {
            LogUtil.error("Disconnecting " + watch.player()
                    + "; CultAC's handlers are still on their dormant connection: " + pipeline.names());
            watch.channel().close();
            return;
        }
        if (pipeline.context(watch) != null) pipeline.remove(watch);
        hooks.resume(watch);
    }

    /** Detaches the connection on its packet owner; completes once that ran or could not. */
    private static CompletableFuture<Void> detachLater(CultConnection connection) {
        return onOwner(connection, () -> detachNow(connection));
    }

    /** Runs {@code task} on the connection's packet owner; completes once it ran or could not. */
    private static CompletableFuture<Void> onOwner(CultConnection connection, Runnable task) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            connection.executeLater(() -> {
                try {
                    task.run();
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
        if (hooks.swaps(connection)) trySwap(hooks, connection, 0);
    }

    /**
     * Swaps the handlers of a detached connection once its encoder settles, trying again until the swap succeeds, the
     * connection or its decoder is gone, or this generation takes no more tasks. A detached connection that still holds
     * CultAC's handlers has one such chain of tries.
     */
    private static void trySwap(Platform hooks, CultConnection connection, int attempt) {
        var channel = connection.channel();
        if (CultAPI.INSTANCE.getNetworkManager().connection(channel) != connection
                || !channel.isActive()
                || channel.pipeline().context(hooks.decoder()) == null) return;
        boolean play = connection.phase(PacketDirection.SERVERBOUND) == ConnectionPhase.PLAY;
        if (swappable(connection) && swap(hooks, connection, play) != null) return;
        retrySwap(hooks, connection, attempt + 1);
    }

    /** Queues swap try {@code attempt}: the first {@link #SWAP_ATTEMPTS} at once, each later one after a pause. */
    private static void retrySwap(Platform hooks, CultConnection connection, int attempt) {
        Runnable retry = () -> trySwap(hooks, connection, attempt);
        if (attempt > SWAP_ATTEMPTS) {
            submit(() -> connection
                    .owner()
                    .schedule(() -> submit(() -> connection.execute(retry)), SLOW_SWAP_MILLIS, TimeUnit.MILLISECONDS));
            return;
        }
        // A swap inside the encoder's own write would discard what that write emits, so each try is a task of its own.
        if (connection.hasClientTickTasks())
            connection.afterClientTick(() -> submit(() -> connection.executeLater(retry)));
        else submit(() -> connection.executeLater(retry));
    }

    /** Submits a swap try; the chain of tries ends where the connection's owner or this generation refuses it. */
    private static void submit(Runnable submission) {
        try {
            submission.run();
        } catch (RejectedExecutionException | IllegalStateException refused) {
            // Nothing of this generation is left to swap.
        }
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
     * whose watch could not be installed is left as it was; one whose handler removal failed has it run once more, and
     * is closed when a CultAC handler is left after that.
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
        try {
            connection
                    .removeHandlers(() -> hooks.removeHandlers(connection))
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            try {
                hooks.removeHandlers(connection);
            } catch (RuntimeException again) {
                cause.addSuppressed(again);
            }
            List<String> left = handlersLeft(pipeline);
            if (!left.isEmpty()) {
                LogUtil.error(
                        "Disconnecting " + name(connection) + "; CultAC's handlers " + left
                                + " stayed on their dormant connection",
                        cause);
                if (pipeline.context(watch) != null) pipeline.remove(watch);
                channel.close();
                return null;
            }
            LogUtil.warn(
                    "Removing CultAC's handlers from " + name(connection) + "'s dormant connection took two tries",
                    cause);
        }
        CultAPI.INSTANCE.getNetworkManager().release(channel);
        watch.retire(connection.drainRetiredPings());
        return watch;
    }

    /** The CultAC handlers on {@code pipeline} but a watch. */
    private static List<String> handlersLeft(ChannelPipeline pipeline) {
        List<String> left = new ArrayList<>();
        for (String name : pipeline.names()) {
            if (name.startsWith("cult-") && !name.equals(DormantWatch.NAME)) left.add(name);
        }
        return left;
    }

    private static String name(CultConnection connection) {
        User user = connection.user();
        return user == null ? String.valueOf(connection.channel().remoteAddress()) : user.getName();
    }
}
