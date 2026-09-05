package ac.cult.cultac.network.event;

import ac.cult.cultac.network.protocol.player.User;
import org.bukkit.entity.Player;

public final class UserDisconnectEvent extends UserEvent {
    public UserDisconnectEvent(User user, Player player) {
        super(user, player);
    }
}
