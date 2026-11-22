package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.network.CultNetworkManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Bukkit account binding; transport lifecycle and packet ownership stay in common. */
final class BukkitConnectionListener implements Listener {
    private final CultNetworkManager network;
    private final PaperDormancy dormancy;

    BukkitConnectionListener(CultNetworkManager network, PaperDormancy dormancy) {
        this.network = network;
        this.dormancy = dormancy;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var connection = BukkitConnectionAdapter.connection(event.getPlayer());
        if (connection != null) network.playerJoined(BukkitConnectionAdapter.channel(connection));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        dormancy.quit(event.getPlayer().getUniqueId());
    }
}
