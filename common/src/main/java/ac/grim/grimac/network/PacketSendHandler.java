package ac.grim.grimac.network;

import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;

@FunctionalInterface
public interface PacketSendHandler<T extends Packet<?>> {
    void handle(PacketSendEvent event, GrimPlayer player, T packet);
}
