package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;

@FunctionalInterface
public interface PacketReceiveHandler<T extends Packet<?>> {
    void handle(PacketReceiveEvent event, CultPlayer player, T packet);
}
