package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.OnlineMode;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.ServerFacts;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

public class BukkitPlatformServer implements PlatformServer {
    private volatile @Nullable ServerFacts cloudFacts;

    @Override
    public String getPlatformImplementationString() {
        return Bukkit.getVersion();
    }

    @Override
    public void dispatchCommand(Sender sender, String command) {
        CommandSender commandSender =
                CultACBukkitLoaderPlugin.INSTANCE.getBukkitSenderFactory().reverse(sender);
        Bukkit.dispatchCommand(commandSender, command);
    }

    @Override
    public Sender getConsoleSender() {
        return CultACBukkitLoaderPlugin.INSTANCE.getBukkitSenderFactory().map(Bukkit.getConsoleSender());
    }

    @Override
    public void registerOutgoingPluginChannel(String name) {
        CultACBukkitLoaderPlugin.LOADER
                .getServer()
                .getMessenger()
                .registerOutgoingPluginChannel(CultACBukkitLoaderPlugin.LOADER, name);
    }

    @Override
    public void forwardAlert(String message) {
        ac.cult.cultac.events.packets.ProxyAlertMessenger.sendPluginMessage(message);
    }

    @Override
    public boolean isProxyForwardingEnabled() {
        return configBoolean("spigot.yml", "settings.bungeecord")
                || configBoolean("config/paper-global.yml", "proxies.velocity.enabled");
    }

    @Override
    public boolean isOnlineMode() {
        try {
            boolean bungee = configBoolean("spigot.yml", "settings.bungeecord");
            boolean bungeeOnline = configBoolean("config/paper-global.yml", "proxies.bungee-cord.online-mode", true);
            boolean velocity = configBoolean("config/paper-global.yml", "proxies.velocity.enabled");
            boolean velocityOnline = configBoolean("config/paper-global.yml", "proxies.velocity.online-mode", true);
            return OnlineMode.authenticated(Bukkit.getOnlineMode(), bungee, bungeeOnline, velocity, velocityOnline);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static boolean configBoolean(String file, String key) {
        return configBoolean(file, key, false);
    }

    private static boolean configBoolean(String file, String key, boolean missing) {
        var path = new java.io.File(file);
        return path.isFile()
                ? org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(path)
                        .getBoolean(key, missing)
                : missing;
    }

    @Override
    public void applyValidationBlock(
            ac.cult.cultac.platform.api.player.PlatformPlayer player,
            int x,
            int y,
            int z,
            String state,
            Runnable applied) {
        BukkitValidationBlocks.apply(player, x, y, z, state, applied);
    }

    @Override
    public double getTPS() {
        // Folia throws UnsupportedOperationException on calling getTPS(), there is no API for getting TPS on Folia
        if (CultAPI.INSTANCE.getPlatform() == Platform.FOLIA) {
            return Double.NaN;
        }
        return Bukkit.getTPS()[0];
    }

    @Override
    public int onlinePlayers() {
        try {
            return Bukkit.getOnlinePlayers().size();
        } catch (RuntimeException | LinkageError unsupported) {
            return -1;
        }
    }

    @Override
    public long[] recentTickNanos() {
        try {
            long[] times = Bukkit.getServer().getTickTimes();
            return times == null ? null : times.clone();
        } catch (RuntimeException | LinkageError unsupported) {
            return null;
        }
    }

    /** The server's software, target tick length and combat settings, read once. */
    @Override
    public ServerFacts cloudFacts() {
        ServerFacts facts = cloudFacts;
        if (facts == null) {
            Map<String, String> settings = new LinkedHashMap<>();
            paperSetting(settings, "update-equipment-on-player-actions", "updateEquipmentOnPlayerActions");
            paperSetting(
                    settings, "skip-vanilla-damage-tick-when-shield-blocked", "skipVanillaDamageTickWhenShieldBlocked");
            String keepAlive = keepAliveIntervalMillis();
            if (keepAlive != null) settings.put("keepalive-interval-ms", keepAlive);
            cloudFacts = facts = new ServerFacts(tickMicros(), Bukkit.getName() + " " + Bukkit.getVersion(), settings);
        }
        return facts;
    }

    private static int tickMicros() {
        try {
            float rate = Bukkit.getServerTickManager().getTickRate();
            return rate > 0 ? Math.round(1_000_000f / rate) : 0;
        } catch (RuntimeException unsupported) {
            return 0;
        }
    }

    /** One of Paper's {@code unsupported-settings}; servers without Paper's global configuration leave it out. */
    private static void paperSetting(Map<String, String> settings, String key, String field) {
        try {
            Object global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration")
                    .getMethod("get")
                    .invoke(null);
            Object unsupported =
                    global.getClass().getField("unsupportedSettings").get(global);
            settings.put(
                    key, String.valueOf(unsupported.getClass().getField(field).get(unsupported)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
        }
    }

    /**
     * Paper's own keepalives go out every second; without them the server sends vanilla's, every
     * {@code LATENCY_CHECK_INTERVAL}. Null when neither can be read.
     */
    private static @Nullable String keepAliveIntervalMillis() {
        try {
            Class.forName("io.papermc.paper.util.KeepAlive");
            return "1000";
        } catch (ClassNotFoundException | LinkageError vanillaKeepAlive) {
        }
        try {
            return String.valueOf(Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl")
                    .getField("LATENCY_CHECK_INTERVAL")
                    .getInt(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }
}
