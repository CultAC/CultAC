package ac.cult.cultac.events.bukkit;

import ac.cult.cultac.CultAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class QuitEvent implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        CultAPI.INSTANCE.getPlayerDataManager().clearExemptions(
                CultAPI.INSTANCE.getPlayerDataManager().getUser(player));
    }

}
