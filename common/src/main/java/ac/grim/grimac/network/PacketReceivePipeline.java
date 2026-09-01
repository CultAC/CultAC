package ac.grim.grimac.network;

import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;

/**
 * Runs decoded serverbound packets through the early and ordinary receive
 * phases. Cancellation is intentionally checked between phases rather than
 * between handlers: every handler in the phase which cancels still observes
 * the packet, while later phases do not.
 */
final class PacketReceivePipeline {
    private PacketReceivePipeline() {
    }

    static void dispatch(
            PacketReceiveRoute earlyRoute,
            PacketReceiveRoute ordinaryRoute,
            PacketReceiveRoute tapRoute,
            PacketReceiveEvent event,
            GrimPlayer player,
            Packet<?> packet
    ) {
        earlyRoute.dispatch(event, player, packet);
        if (event.isCancelled()) {
            return;
        }

        ordinaryRoute.dispatch(event, player, packet);
        tapRoute.dispatch(event, player, event.getNmsPacket());
    }
}
