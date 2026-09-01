package ac.grim.grimac.events.bukkit;

import ac.grim.grimac.GrimAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class QuitEvent implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        GrimAPI.INSTANCE.getPlayerDataManager().clearExemptions(
                GrimAPI.INSTANCE.getPlayerDataManager().getUser(player));
    }

}
