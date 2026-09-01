package ac.grim.grimac.network.event;

import ac.grim.grimac.network.protocol.player.User;
import org.bukkit.entity.Player;

public final class UserConnectEvent extends UserEvent {
    public UserConnectEvent(User user, Player player) {
        super(user, player);
    }
}
