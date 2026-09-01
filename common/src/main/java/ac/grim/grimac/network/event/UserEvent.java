package ac.grim.grimac.network.event;

import ac.grim.grimac.network.protocol.player.User;
import org.bukkit.entity.Player;

public abstract class UserEvent {
    private final User user;
    private final Player player;
    private boolean cancelled;

    protected UserEvent(User user, Player player) {
        this.user = user;
        this.player = player;
    }

    public User getUser() {
        return user;
    }

    public Player getPlayer() {
        return player;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
