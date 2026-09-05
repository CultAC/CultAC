package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import org.bukkit.entity.Player;

public class ExemptOnlinePlayersOnReload implements StartableInitable {

    // Runs on plugin startup adding all online players to exempt list; will be empty unless reload
    // This essentially exists to stop you from shooting yourself in the foot by being stupid and using /reload
    @Override
    public void start() {
        for (PlatformPlayer player : CultAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers()) {
            User user = CultAPI.INSTANCE.getPlayerDataManager().getUser((Player) player.getNative());
            if (user != null) {
                CultAPI.INSTANCE.getPlayerDataManager().exemptUser(user);
            }
        }
    }
}
