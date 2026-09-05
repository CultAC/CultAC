package ac.cult.cultac.packet;

import net.minecraft.network.protocol.Packet;

import java.util.List;

/**
 * A function handling an incoming or outgoing packet.
 *
 * @param <R> the receiver type, such as a Bukkit player, NMS player, or Paper connection
 * @param <T> the packet type handled by this function
 */
@FunctionalInterface
public interface PacketHandlerFunction<R, T extends Packet<?>> {
    List<Packet<?>> invoke(R receiver, T packet) throws Exception;

    default List<Packet<?>> handle(R receiver, T packet) throws Exception {
        return invoke(receiver, packet);
    }
}
