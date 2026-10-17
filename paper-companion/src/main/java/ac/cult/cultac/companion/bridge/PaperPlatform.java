package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage.Ready;
import ac.cult.cultac.companion.MainThread;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

final class PaperPlatform implements BridgePlatform {
    private final Plugin plugin;
    private final String serverVersion;

    PaperPlatform(Plugin plugin) {
        this.plugin = plugin;
        this.serverVersion = Bukkit.getName() + " " + Bukkit.getVersion();
    }

    @Override
    public void runMain(Runnable task) {
        MainThread.runMain(plugin, task);
    }

    @Override
    public @Nullable Player player(UUID player) {
        return Bukkit.getPlayer(player);
    }

    @Override
    public @Nullable Ready ready(UUID player, byte[] hmac, long capabilities, int listeners) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) return null;
        World world = online.getWorld();
        return new Ready(hmac, capabilities, serverVersion, world.getUID(), world.getName(), listeners);
    }
}
