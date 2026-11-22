package ac.cult.cultac.manager.player;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.routing.NetSettings;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.player.CultPlayer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies the network's shared observe, punish and check settings to the players this generation inspects: to every
 * player when one of those settings changes, and to one player when they join or switch backend server. Each player
 * applies them on their packet owner, from which the checks read them.
 */
public final class PlayerNetworkSettings {
    private static final AtomicBoolean started = new AtomicBoolean();

    private PlayerNetworkSettings() {}

    /** Starts applying them; the first call of a generation counts. */
    public static void start() {
        if (!started.compareAndSet(false, true)) return;
        NetworkModule.onSettingsChanged(change -> {
            if (!change.affects(NetSettings.OBSERVE) && !change.affects(NetSettings.PUNISH) && !change.affectsChecks())
                return;
            for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) apply(player);
        });
        NetworkModule.onPlayerChanged(uuid -> {
            CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid);
            if (player != null) apply(player);
        });
    }

    private static void apply(CultPlayer player) {
        player.runSafely(player::applyNetworkSettings);
    }
}
