package ac.cult.cultac.network.protocol.util;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

// this class only exists to let the FlightA joke continue on since they haven't been called
// flying packets in a long, long time. This copies the form of PacketEvent 2.0's wrapper.
public final class WrapperPlayClientPlayerFlying {
    private WrapperPlayClientPlayerFlying() {
    }

    public static boolean isFlying(PacketReceiveEvent event) {
        return event.getNmsPacket() instanceof ServerboundMovePlayerPacket;
    }
}
