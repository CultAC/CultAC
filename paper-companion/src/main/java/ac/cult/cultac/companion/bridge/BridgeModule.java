package ac.cult.cultac.companion.bridge;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/** Owns the netty handler, session handshake and server actions. */
public final class BridgeModule {
    private static final CompanionBridge DISABLED = new CompanionBridge() {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public @Nullable CompanionSession session(UUID player) {
            return null;
        }

        @Override
        public @Nullable CompanionSession anySession() {
            return null;
        }

        @Override
        public void addHandler(CompanionMessageHandler handler) {}

        @Override
        public void setListenerBits(int listenerKindBits) {}

        @Override
        public void addCapabilities(long capabilityMask) {}
    };

    private static @Nullable BridgeService service;
    private static @Nullable BridgeInjector injector;
    private static @Nullable BridgeListener listener;
    private static @Nullable TickMarkers markers;
    private static @Nullable BukkitTask ticker;

    /** A shorter configured secret can be guessed offline from a handshake the proxy answers. */
    private static final int MIN_SECRET_BYTES = 16;

    private BridgeModule() {}

    public static CompanionBridge enable(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        GlobalConfiguration.Proxies.Velocity velocity = GlobalConfiguration.get().proxies.velocity;
        String secret = config.getString("secret", "");
        boolean ownSecret = true;
        if (secret == null || secret.isEmpty()) {
            if (!velocity.enabled) {
                plugin.getLogger()
                        .warning("Velocity modern forwarding is off (proxies.velocity.enabled in"
                                + " config/paper-global.yml) and no secret is set in"
                                + " plugins/CultAC-Companion/config.yml; the CultAC proxy bridge stays disabled.");
                return DISABLED;
            }
            secret = velocity.secret;
            ownSecret = false;
        }
        if (secret == null || secret.isEmpty()) {
            plugin.getLogger()
                    .warning("No bridge secret: set proxies.velocity.secret in config/paper-global.yml or secret in"
                            + " plugins/CultAC-Companion/config.yml; the CultAC proxy bridge stays disabled.");
            return DISABLED;
        }
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (ownSecret && key.length < MIN_SECRET_BYTES) {
            plugin.getLogger()
                    .warning("The secret in plugins/CultAC-Companion/config.yml is shorter than " + MIN_SECRET_BYTES
                            + " bytes; use a long random value");
        }
        try {
            MethodHandles.lookup().ensureInitialized(BridgeChannelHandler.class);
        } catch (LinkageError | IllegalAccessException unsupported) {
            plugin.getLogger()
                    .log(
                            Level.WARNING,
                            "This server's custom payloads are unsupported; the CultAC proxy bridge stays disabled.",
                            unsupported);
            return DISABLED;
        }
        BridgeService bridge = new BridgeService(
                key, new PaperPlatform(plugin), plugin.getLogger(), config.getBoolean("debug", false));
        bridge.addHandler(new ServerActions(plugin, bridge));
        TickMarkers tickMarkers = new TickMarkers();
        bridge.addHandler(tickMarkers);
        BridgeInjector netty = new BridgeInjector(bridge);
        netty.install();
        BridgeListener events = new BridgeListener(bridge);
        Bukkit.getPluginManager().registerEvents(events, plugin);
        Bukkit.getPluginManager().registerEvents(tickMarkers, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, new BridgeTicker(bridge), 20L, 20L);
        service = bridge;
        injector = netty;
        listener = events;
        markers = tickMarkers;
        plugin.getLogger()
                .info("Bridge enabled (secret from " + (ownSecret ? "companion config" : "Paper velocity config")
                        + "); waiting for the CultAC proxy");
        return bridge;
    }

    public static void disable() {
        if (service != null) service.closeAll("companion disabled");
        if (ticker != null) ticker.cancel();
        if (listener != null) HandlerList.unregisterAll(listener);
        if (markers != null) HandlerList.unregisterAll(markers);
        if (injector != null) injector.uninstall();
        ticker = null;
        listener = null;
        markers = null;
        injector = null;
        service = null;
    }
}
