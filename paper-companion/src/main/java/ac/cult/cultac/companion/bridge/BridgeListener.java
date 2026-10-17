package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import io.netty.channel.Channel;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Handles a HELLO held until join, ends sessions on quit and reports world changes. */
final class BridgeListener implements Listener {
    private final BridgeService service;

    BridgeListener(BridgeService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!(event.getPlayer() instanceof CraftPlayer player)) return;
        Channel channel = player.getHandle().connection.connection.channel;
        if (channel != null
                && channel.pipeline().get(BridgeChannelHandler.NAME) instanceof BridgeChannelHandler handler) {
            channel.eventLoop().execute(handler::attached);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.close(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        CompanionSession session = service.session(event.getPlayer().getUniqueId());
        if (session == null) return;
        World world = event.getPlayer().getWorld();
        session.send(new BridgeMessage.World(world.getUID(), world.getName()));
    }
}
