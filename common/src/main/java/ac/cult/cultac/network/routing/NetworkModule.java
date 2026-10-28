package ac.cult.cultac.network.routing;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.RoutingHolder;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Runs this runtime's {@link NetworkService}: started with the plugin, restarted by a reload that changes the
 * {@code network} block, stopped on disable. The platform names itself with {@link #platform} before the plugin
 * starts; without one there is no service. While a transport runs, the service is {@code NetworkRouting.current()}.
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

    public static void start() {
        start(NetworkSettings.read(CultAPI.INSTANCE.getConfigManager().getConfig()));
    }

    static synchronized void start(NetworkSettings settings) {
        if (service == null) begin(settings);
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
        end();
        begin(settings);
    }

    public static synchronized void stop() {
        end();
    }

    /** A join or backend switch on this instance. Any thread. */
    public static void playerChanged(UUID uuid, String name, @Nullable String server) {
        NetworkService running = service;
        if (running != null) running.playerChanged(new OnlinePlayer(uuid, name, server));
    }

    /** A quit on this instance. Any thread. */
    public static void playerLeft(UUID uuid) {
        NetworkService running = service;
        if (running != null) running.playerLeft(uuid);
    }

    private static void begin(NetworkSettings settings) {
        NetworkPlatform hooks = platform;
        if (hooks == null) return;
        NetworkService started = NetworkService.create(settings, hooks, RuntimeEnvironment.host());
        for (Consumer<NetworkService> hook : startHooks) {
            try {
                hook.accept(started);
            } catch (RuntimeException failure) {
                LogUtil.warn("A network feature failed to start", failure);
            }
        }
        started.start();
        service = started;
        if (!started.enabled()) return;
        RoutingHolder.install(started);
        LogUtil.info("Network routing on over " + started.transportName() + " as instance " + started.instanceId()
                + " of network " + settings.network());
    }

    private static void end() {
        NetworkService running = service;
        service = null;
        if (running == null) return;
        RoutingHolder.uninstall(running);
        running.stop();
    }
}
