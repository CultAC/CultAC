package ac.cult.cultac.packet;

import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utilities for treating bundle packets as their logical packet stream.
 */
public final class PacketBundleUtil {
    private PacketBundleUtil() {
    }

    public static List<Packet<?>> flattenOneLevel(Packet<?> packet) {
        if (packet == null) {
            return Collections.emptyList();
        }
        if (!(packet instanceof BundlePacket<?> bundle)) {
            return List.of(packet);
        }

        List<Packet<?>> packets = new ArrayList<>();
        for (Packet<?> subPacket : subPackets(bundle)) {
            if (subPacket != null) {
                packets.add(subPacket);
            }
        }
        return packets;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Iterable<Packet<?>> subPackets(BundlePacket<?> bundle) {
        return (Iterable<Packet<?>>) (Iterable) bundle.subPackets();
    }
}
