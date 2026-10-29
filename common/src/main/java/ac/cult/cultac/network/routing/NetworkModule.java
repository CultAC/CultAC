package ac.cult.cultac.network.routing;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Runs this runtime's {@link NetworkService}: started with the plugin, restarted by a reload that changes the
 * {@code network} block, stopped on disable. The platform names itself with {@link #platform} before the plugin
 * starts; without one there is no service. The running service is {@code NetworkRouting.current()}; without a
 * transport it knows only this instance and reaches its other engine generations.
 */
public final class NetworkModule {
    private static final List<Consumer<NetworkService>> startHooks = new CopyOnWriteArrayList<>();
    private static volatile @Nullable NetworkPlatform platform;
    private static volatile @Nullable NetworkService service;

    private NetworkModule() {}

    public static void platform(@Nullable NetworkPlatform hooks) {
        platform = hooks;
    }

    /** Runs {@code hook} on each service this runtime starts from now on, before it subscribes. */
    public static void onStart(Consumer<NetworkService> hook) {
        startHooks.add(hook);
    }

    /** The running service, which later features register handlers on and send through; null before start. */
    public static @Nullable NetworkService service() {
        return service;
    }

    /** The running service, else {@link #NO_ROUTING}. */
    public static NetworkRouting routing() {
        NetworkService running = service;
        return running == null ? NO_ROUTING : running;
    }

    /** Whether this instance is a proxy, whose players are on backend servers. */
    public static boolean proxy() {
        NetworkPlatform hooks = platform;
        return hooks != null && hooks.proxy();
    }

    public static void start() {
        start(NetworkSettings.read(CultAPI.INSTANCE.getConfigManager().getConfig()));
    }

    static synchronized void start(NetworkSettings settings) {
        NetworkPlatform hooks = platform;
        if (service != null || hooks == null) return;
        NetworkService created = NetworkService.create(settings, hooks, RuntimeEnvironment.host());
        for (Consumer<NetworkService> hook : startHooks) {
            try {
                hook.accept(created);
            } catch (RuntimeException failure) {
                LogUtil.warn("A network feature failed to start", failure);
            }
        }
        start(created);
    }

    /** Starts {@code created} as the running service, unless one runs. */
    static synchronized void start(NetworkService created) {
        if (service != null) return;
        created.start();
        service = created;
    }

    /** Reconnects when the reloaded {@code network} block differs from the running one. */
    public static void configReloaded() {
        if (service != null)
            configReloaded(
                    NetworkSettings.read(CultAPI.INSTANCE.getConfigManager().getConfig()));
    }

    static synchronized void configReloaded(NetworkSettings settings) {
        NetworkService running = service;
        if (running == null || settings.equals(running.settings())) return;
        end(NetworkModule::closeInBackground);
        start(settings);
    }

    public static synchronized void stop() {
        end(Runnable::run);
    }

    private static void closeInBackground(Runnable close) {
        Thread thread = new Thread(close, "CultAC network close");
        thread.setDaemon(true);
        thread.start();
    }

    /** A join or backend switch on this instance; {@code brand} is the client brand, null while it is not known. */
    public static void playerChanged(UUID uuid, String name, @Nullable String server, @Nullable String brand) {
        NetworkService running = service;
        if (running != null) running.playerChanged(new OnlinePlayer(uuid, name, server, brand));
    }

    /** The client brand of a player on this instance arrived after their join. Any thread. */
    public static void brandChanged(UUID uuid, @Nullable String brand) {
        NetworkService running = service;
        if (running != null) running.brandChanged(uuid, brand);
    }

    /** A quit on this instance. Any thread. */
    public static void playerLeft(UUID uuid) {
        NetworkService running = service;
        if (running != null) running.playerLeft(uuid);
    }

    private static void end(Executor closing) {
        NetworkService running = service;
        service = null;
        if (running != null) running.stop(closing);
    }

    /** The routing before the service starts: it knows no player and runs nothing. */
    private static final NetworkRouting NO_ROUTING = new NetworkRouting() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String instanceId() {
            return "local";
        }

        @Override
        public Optional<NetworkPlayer> locate(String nameOrUuid) {
            return Optional.empty();
        }

        @Override
        public Collection<NetworkPlayer> roster() {
            return List.of();
        }

        @Override
        public CompletableFuture<Boolean> run(Sender sender, String line, NetworkPlayer where) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return CompletableFuture.completedFuture(false);
        }
    };
}
