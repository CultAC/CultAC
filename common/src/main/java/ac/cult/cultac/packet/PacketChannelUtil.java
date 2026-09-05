package ac.cult.cultac.packet;

import io.netty.channel.Channel;
import net.minecraft.network.protocol.Packet;

import java.lang.reflect.Method;

/**
 * Shared channel helpers for packet bridge callers.
 */
public final class PacketChannelUtil {
    private PacketChannelUtil() {
    }

    public static void execute(Channel channel, Runnable task) {
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }

    public static Packet<?> unwrapPacket(Object packet) {
        if (packet instanceof Packet<?> nmsPacket) {
            return nmsPacket;
        }
        if (packet == null) {
            return null;
        }
        try {
            Method getPacket = packet.getClass().getMethod("getPacket");
            Object candidate = getPacket.invoke(packet);
            return candidate instanceof Packet<?> nmsPacket ? nmsPacket : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
