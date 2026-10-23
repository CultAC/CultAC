package ac.cult.cultac.platform.bukkit.player;

import ac.cult.cultac.platform.api.player.AbstractPlatformPlayerFactory;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import java.util.Collection;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class BukkitPlatformPlayerFactory extends AbstractPlatformPlayerFactory<Player> {

    @Override
    protected Player getNativePlayer(@NotNull UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }

    @Override
    protected Player getNativePlayer(@NotNull String name) {
        return Bukkit.getPlayer(name);
    }

    @Override
    protected PlatformPlayer createPlatformPlayer(@NotNull Player nativePlayer) {
        return new BukkitPlatformPlayer(nativePlayer);
    }

    @Override
    protected UUID getPlayerUUID(@NotNull Player nativePlayer) {
        return nativePlayer.getUniqueId();
    }

    // The cast is safe because Bukkit.getOnlinePlayers() is guaranteed to contain Player or its subtypes,
    // and we're only reading from it.
    @Override
    @SuppressWarnings("unchecked")
    protected Collection<Player> getNativeOnlinePlayers() {
        return (Collection<Player>) Bukkit.getOnlinePlayers();
    }
}
