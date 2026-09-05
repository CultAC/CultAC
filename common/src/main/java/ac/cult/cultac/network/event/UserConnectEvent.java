package ac.cult.cultac.network.event;

import ac.cult.cultac.network.protocol.player.User;
import org.bukkit.entity.Player;

public final class UserConnectEvent extends UserEvent {
    public UserConnectEvent(User user, Player player) {
        super(user, player);
    }
}
