package ac.cult.cultac.packet;

import net.minecraft.network.protocol.Packet;

import java.util.List;

/**
 * A packet handler that also receives packet bridge context.
 */
@FunctionalInterface
public interface PacketContextHandlerFunction<R, T extends Packet<?>> {
    List<Packet<?>> invoke(PacketContext context, R receiver, T packet) throws Exception;

    default List<Packet<?>> handle(PacketContext context, R receiver, T packet) throws Exception {
        return invoke(context, receiver, packet);
    }
}
