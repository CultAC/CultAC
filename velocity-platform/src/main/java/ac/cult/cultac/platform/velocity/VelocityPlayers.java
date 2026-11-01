package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.platform.api.player.AbstractPlatformPlayerFactory;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

final class VelocityPlayers extends AbstractPlatformPlayerFactory<Player> {
    private final ProxyServer proxy;
    private final VelocitySenders senders;

    VelocityPlayers(ProxyServer proxy, VelocitySenders senders) {
        this.proxy = proxy;
        this.senders = senders;
    }

    @Override
    protected Player getNativePlayer(UUID id) {
        return proxy.getPlayer(id).orElse(null);
    }

    @Override
    protected Player getNativePlayer(String name) {
        return proxy.getPlayer(name).orElse(null);
    }

    @Override
    protected UUID getPlayerUUID(Player player) {
        return player.getUniqueId();
    }

    @Override
    protected Collection<Player> getNativeOnlinePlayers() {
        return List.copyOf(proxy.getAllPlayers());
    }

    @Override
    protected PlatformPlayer createPlatformPlayer(Player player) {
        return new VelocityPlayer(player, senders);
    }
}
