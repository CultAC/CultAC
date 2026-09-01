package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import org.bukkit.entity.Player;

public class ExemptOnlinePlayersOnReload implements StartableInitable {

    // Runs on plugin startup adding all online players to exempt list; will be empty unless reload
    // This essentially exists to stop you from shooting yourself in the foot by being stupid and using /reload
    @Override
    public void start() {
        for (PlatformPlayer player : GrimAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers()) {
            User user = GrimAPI.INSTANCE.getPlayerDataManager().getUser((Player) player.getNative());
            if (user != null) {
                GrimAPI.INSTANCE.getPlayerDataManager().exemptUser(user);
            }
        }
    }
}
