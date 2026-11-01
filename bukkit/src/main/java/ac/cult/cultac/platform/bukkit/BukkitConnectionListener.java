package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.network.CultNetworkManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Bukkit account binding; transport lifecycle and packet ownership stay in common. */
final class BukkitConnectionListener implements Listener {
    private final CultNetworkManager network;

    BukkitConnectionListener(CultNetworkManager network) {
        this.network = network;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var connection = BukkitConnectionAdapter.connection(event.getPlayer());
        if (connection != null) network.playerJoined(BukkitConnectionAdapter.channel(connection));
    }
}
