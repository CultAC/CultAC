package ac.grim.grimac.network;

import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;

@FunctionalInterface
public interface PacketReceiveHandler<T extends Packet<?>> {
    void handle(PacketReceiveEvent event, GrimPlayer player, T packet);
}
