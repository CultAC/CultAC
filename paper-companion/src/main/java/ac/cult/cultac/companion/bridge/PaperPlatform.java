package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage.CloudFacts;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerFacts;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Ready;
import ac.cult.cultac.companion.MainThread;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

final class PaperPlatform implements BridgePlatform {
    /** The attack speed base at and above which a server runs 1.8-style combat. */
    private static final double LEGACY_ATTACK_SPEED = 16;

    private final Plugin plugin;
    private final String serverVersion;
    private final CloudFacts cloudFacts;

    PaperPlatform(Plugin plugin) {
        this.plugin = plugin;
        this.serverVersion = Bukkit.getName() + " " + Bukkit.getVersion();
        this.cloudFacts = readCloudFacts();
    }

    @Override
    public void runMain(Runnable task) {
        MainThread.runMain(plugin, task);
    }

    @Override
    public @Nullable Player player(UUID player) {
        return Bukkit.getPlayer(player);
    }

    @Override
    public @Nullable Ready ready(UUID player, byte[] hmac, long capabilities, int listeners) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) return null;
        World world = online.getWorld();
        return new Ready(hmac, capabilities, serverVersion, world.getUID(), world.getName(), listeners);
    }

    @Override
    public CloudFacts cloudFacts() {
        return cloudFacts;
    }

    @Override
    public @Nullable PlayerFacts playerFactsChanged(BridgeSession session) {
        if (!(session.player instanceof CraftPlayer player)) return null;
        AttributeInstance attackSpeed = player.getHandle().getAttribute(Attributes.ATTACK_SPEED);
        double base = attackSpeed == null ? Double.NaN : attackSpeed.getBaseValue();
        int maxNoDamageTicks = player.getMaximumNoDamageTicks();
        if (!session.playerFactsChanged(maxNoDamageTicks, base)) return null;
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("max-no-damage-ticks", String.valueOf(maxNoDamageTicks));
        if (!Double.isNaN(base)) {
            settings.put("attack-speed-base", String.valueOf(base));
            settings.put("combat-mode", base >= LEGACY_ATTACK_SPEED ? "1.8" : "1.9");
        }
        return new PlayerFacts(settings);
    }

    private static CloudFacts readCloudFacts() {
        Map<String, String> settings = new LinkedHashMap<>();
        try {
            GlobalConfiguration.UnsupportedSettings unsupported = GlobalConfiguration.get().unsupportedSettings;
            settings.put(
                    "update-equipment-on-player-actions", String.valueOf(unsupported.updateEquipmentOnPlayerActions));
            settings.put(
                    "skip-vanilla-damage-tick-when-shield-blocked",
                    String.valueOf(unsupported.skipVanillaDamageTickWhenShieldBlocked));
        } catch (RuntimeException | LinkageError unreadable) {
        }
        String keepAlive = keepAliveIntervalMillis();
        if (keepAlive != null) settings.put("keepalive-interval-ms", keepAlive);
        return new CloudFacts(tickMicros(), settings);
    }

    private static int tickMicros() {
        try {
            float rate = Bukkit.getServerTickManager().getTickRate();
            return rate > 0 ? Math.round(1_000_000f / rate) : 0;
        } catch (RuntimeException unsupported) {
            return 0;
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
