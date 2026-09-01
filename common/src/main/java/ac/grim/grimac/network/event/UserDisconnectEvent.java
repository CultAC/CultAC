package ac.grim.grimac.network.event;

import ac.grim.grimac.network.protocol.player.User;
import org.bukkit.entity.Player;

public final class UserDisconnectEvent extends UserEvent {
    public UserDisconnectEvent(User user, Player player) {
        super(user, player);
    }
}
