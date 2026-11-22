package ac.cult.cultac.network.protocol.util;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;

/** Whether a received packet is a player move, the packet older protocols name flying. */
public final class WrapperPlayClientPlayerFlying {
    private WrapperPlayClientPlayerFlying() {}

    public static boolean isFlying(PacketReceiveEvent event) {
        return event.getPacket() instanceof ServerboundMovePlayer;
    }
}
