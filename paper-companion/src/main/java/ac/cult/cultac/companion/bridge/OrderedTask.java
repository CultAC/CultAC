package ac.cult.cultac.companion.bridge;

import java.util.concurrent.RejectedExecutionException;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerLevel;

/** A main-thread task queued in the server's packet queue, so it runs in order with its connection's game packets. */
record OrderedTask(Runnable task) implements Packet<ServerCommonPacketListener> {
    /** Call on the connection's event loop; false when the listener takes no common packets or the queue is closed. */
    static boolean schedule(ServerLevel level, PacketListener listener, Runnable task) {
        if (!(listener instanceof ServerCommonPacketListener common)) return false;
        // Every game packet takes this queue: the server's own tasks before 1.21.9, its PacketProcessor after.
        try {
            PacketUtils.ensureRunningOnSameThread(new OrderedTask(task), common, level);
        } catch (RunningOnDifferentThreadException queued) {
            return true;
        } catch (RejectedExecutionException closed) {
            return false;
        }
        task.run();
        return true;
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
