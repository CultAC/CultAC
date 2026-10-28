package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.network.routing.NetworkPlatform;
import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** The server's side of the network service: its online players and their joins and quits. */
public final class BukkitNetwork implements NetworkPlatform, Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        NetworkModule.playerChanged(player.getUniqueId(), player.getName(), null);
    }

    /** The quitting player is still among the online players until this event returns. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        NetworkModule.playerLeft(event.getPlayer().getUniqueId());
    }

    @Override
    public Collection<OnlinePlayer> onlinePlayers() {
        return Bukkit.getOnlinePlayers().stream()
                .map(player -> new OnlinePlayer(player.getUniqueId(), player.getName(), null))
                .toList();
    }

    @Override
    public String address() {
        return NetworkPlatform.address(Bukkit.getIp(), Bukkit.getPort());
    }
}
