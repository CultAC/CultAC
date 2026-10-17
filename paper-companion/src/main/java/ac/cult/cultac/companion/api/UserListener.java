package ac.cult.cultac.companion.api;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Keeps a Grim user for every online player, available to other plugins' join and quit handlers. */
final class UserListener implements Listener {
    private final CompanionGrimAPI api;
    private final GrimApiEvents events;

    UserListener(CompanionGrimAPI api, GrimApiEvents events) {
        this.api = api;
        this.events = events;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        api.join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        events.playerQuit(event.getPlayer());
    }
}
