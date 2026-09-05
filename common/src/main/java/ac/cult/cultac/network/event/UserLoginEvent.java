package ac.cult.cultac.network.event;

import ac.cult.cultac.network.protocol.player.User;
import org.bukkit.entity.Player;

public final class UserLoginEvent extends UserEvent {
    public UserLoginEvent(User user, Player player) {
        super(user, player);
    }
}
