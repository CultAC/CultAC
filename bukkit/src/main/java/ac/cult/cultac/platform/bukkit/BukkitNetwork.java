package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.network.routing.NetworkPlatform;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The server's side of the network service: its online players, their joins and quits, and the Velocity forwarding
 * secret it shares with its proxy.
 */
public final class BukkitNetwork implements NetworkPlatform, Listener {
    private static final String PAPER_GLOBAL = "config/paper-global.yml";

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        NetworkModule.playerChanged(player.getUniqueId(), player.getName(), null, player.getClientBrandName());
    }

    /** The quitting player is still among the online players until this event returns. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        NetworkModule.playerLeft(event.getPlayer().getUniqueId());
    }

    @Override
    public Collection<OnlinePlayer> onlinePlayers() {
        return Bukkit.getOnlinePlayers().stream()
                .map(player ->
                        new OnlinePlayer(player.getUniqueId(), player.getName(), null, player.getClientBrandName()))
                .toList();
    }

    @Override
    public Object remoteSenderBase() {
        return Bukkit.getConsoleSender();
    }

    @Override
    public String address() {
        return NetworkPlatform.address(Bukkit.getIp(), Bukkit.getPort());
    }

    @Override
    public byte @Nullable [] defaultSecret() {
        return velocitySecret(BukkitPlatformServer.config(PAPER_GLOBAL));
    }

    /** {@code proxies.velocity.secret} of Paper's global config while Velocity forwarding is on; null otherwise. */
    static byte @Nullable [] velocitySecret(@Nullable YamlConfiguration paperGlobal) {
        if (paperGlobal == null || !paperGlobal.getBoolean("proxies.velocity.enabled")) return null;
        String secret = paperGlobal.getString("proxies.velocity.secret", "");
        return secret == null || secret.isEmpty() ? null : secret.getBytes(StandardCharsets.UTF_8);
    }
}
