package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;

@FunctionalInterface
public interface PacketSendHandler<T extends Packet<?>> {
    void handle(PacketSendEvent event, CultPlayer player, T packet);
}
