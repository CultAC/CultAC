package ac.cult.cultac.companion.api;

import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.companion.MainThread;
import ac.cult.cultac.companion.bridge.CompanionBridge;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.internal.event.OptimizedEventBus;
import ac.grim.grimac.internal.platform.bukkit.resolver.BukkitResolverRegistrar;
import ac.grim.grimac.internal.plugin.resolver.GrimExtensionManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/** Exposes the Grim API on the backend, fed by the bridge. */
public final class ApiModule {
    private static final long LISTENER_CHECK_TICKS = 20L;

    private static CompanionGrimAPI api;
    private static GrimApiEvents events;
    private static BukkitTask listenerCheck;
    private static ExecutorService legacyEvents;
    private static UserListener users;

    private ApiModule() {}

    public static void enable(JavaPlugin plugin, CompanionBridge bridge) {
        legacyEvents = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CultAC companion legacy events");
            thread.setDaemon(true);
            return thread;
        });
        GrimExtensionManager extensions = new GrimExtensionManager();
        new BukkitResolverRegistrar().registerAll(extensions);
        api = new CompanionGrimAPI(
                new OptimizedEventBus(extensions),
                extensions,
                plugin.getPluginMeta().getVersion(),
                plugin.getLogger(),
                task -> MainThread.runMain(plugin, task),
                Bukkit::getCurrentTick);
        api.checkPermissions(new CheckPermissions(task -> MainThread.runMain(plugin, task), ApiModule::registerFalse));
        events = new GrimApiEvents(
                api,
                api.getGrimPlugin(plugin),
                bridge,
                new LegacyEvents(ApiModule::callAsync),
                System::currentTimeMillis,
                (task, ticks) -> Bukkit.getScheduler().runTaskLater(plugin, task, ticks)::cancel,
                command -> dispatchConsole(plugin, command));
        users = new UserListener(api, events);
        Bukkit.getPluginManager().registerEvents(users, plugin);
        for (Player online : Bukkit.getOnlinePlayers()) api.join(online);
        bridge.addCapabilities(ServerCapability.GRIM_API.flag() | ServerCapability.FLAG_VERDICT.flag());
        bridge.addHandler(events);

        try {
            GrimAPIProvider.init(api);
        } catch (IllegalStateException alreadyInitialized) {
            plugin.getLogger()
                    .warning("GrimAPIProvider was initialized before the companion enabled (a reload?); plugins using"
                            + " GrimAPIProvider.get() keep the earlier instance, the Bukkit service is current");
        }
        // Another plugin may register its own GrimAbstractAPI; High priority makes this one resolve first.
        Bukkit.getServicesManager().register(GrimAbstractAPI.class, api, plugin, ServicePriority.High);
        api.markStarted();
        events.refreshListeners();
        listenerCheck = Bukkit.getScheduler()
                .runTaskTimer(plugin, events::refreshListeners, LISTENER_CHECK_TICKS, LISTENER_CHECK_TICKS);
    }

    public static void disable() {
        if (api == null) return;
        listenerCheck.cancel();
        events.disable();
        HandlerList.unregisterAll(users);
        Bukkit.getServicesManager().unregister(GrimAbstractAPI.class, api);
        api.clear();
        legacyEvents.shutdown();
        legacyEvents = null;
        users = null;
        listenerCheck = null;
        events = null;
        api = null;
    }

    /** Routes Grim API {@code reload*} calls; null leaves them unsupported. */
    public static void reloadVia(@Nullable Supplier<CompletableFuture<Boolean>> reloader) {
        if (api != null) api.reloadVia(reloader);
    }

    private static CompletableFuture<Boolean> dispatchConsole(JavaPlugin plugin, String command) {
        CompletableFuture<Boolean> dispatched = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    dispatched.complete(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
                } catch (Throwable failure) {
                    plugin.getLogger().log(Level.WARNING, "Proxy punishment command failed: " + command, failure);
                    dispatched.complete(false);
                }
            });
        } catch (RuntimeException disabled) {
            dispatched.complete(false);
        }
        return dispatched;
    }

    private static void registerFalse(String node) {
        if (Bukkit.getPluginManager().getPermission(node) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(node, PermissionDefault.FALSE));
        }
    }

    /** Legacy Grim events are async and Bukkit rejects them on the main thread; there they are queued, never awaited. */
    private static void callAsync(Event event) {
        if (Bukkit.isPrimaryThread()) {
            ExecutorService executor = legacyEvents;
            if (executor != null)
                executor.execute(() -> Bukkit.getPluginManager().callEvent(event));
        } else {
            Bukkit.getPluginManager().callEvent(event);
        }
    }
}
