package ac.grim.grimac.network.event;

import ac.grim.grimac.network.protocol.player.User;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;

public final class PacketSendEvent extends PacketEvent {
    public PacketSendEvent(User user, Packet<?> packet, ConnectionProtocol connectionState) {
        super(user, packet, connectionState);
    }

    public PacketSendEvent(User user, Packet<?> packet, ConnectionProtocol connectionState, boolean insideBundle) {
        super(user, packet, connectionState, insideBundle);
    }
}
