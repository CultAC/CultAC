package ac.cult.cultac.companion.bridge;

import java.util.concurrent.RejectedExecutionException;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/** A main-thread task queued in the server's packet queue, so it runs in order with its connection's game packets. */
record OrderedTask(Runnable task) implements Packet<ServerCommonPacketListener> {
    /** Call on the connection's event loop; false when the listener takes no common packets or the queue is closed. */
    static boolean schedule(PacketProcessor processor, PacketListener listener, Runnable task) {
        if (!(listener instanceof ServerCommonPacketListener common)) return false;
        try {
            processor.scheduleIfPossible(common, new OrderedTask(task));
            return true;
        } catch (RejectedExecutionException closed) {
            return false;
        }
    }

    // PacketProcessor queues only typed game packets; this type is never encoded.
    @Override
    public PacketType<ServerboundCustomPayloadPacket> type() {
        return CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD;
    }

    @Override
    public void handle(ServerCommonPacketListener listener) {
        task.run();
    }
}
