package ac.cult.cultac.checks.type;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;

/** Packet classifications that legacy-style check behavior depends on. */
public final class LegacyPacketEventSemantics {
    private LegacyPacketEventSemantics() {}

    /** Serverbound PLAY packets that legacy-style checks treat as async. */
    public static boolean isAsync(PacketReceiveEvent<?> event) {
        var type = event.getPacketType();
        return type == ServerboundPackets.KEEP_ALIVE
                || type == ServerboundPackets.CHUNK_BATCH_RECEIVED
                || type == ServerboundPackets.RESOURCE_PACK;
    }
}
