package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.netty.CultDecoder;

/** Sends a packet to the backend as if the client had sent it, after Cult's own receive handling. */
final class VelocityServerbound {
    private VelocityServerbound() {}

    static <R> void inject(CultConnection connection, PacketType<R> type, R packet) {
        var channel = connection.channel();
        channel.eventLoop().execute(() -> {
            // Frames enter after Cult's decoder in the observed format Velocity's decoder expects.
            var context = channel.pipeline().context(CultDecoder.NAME);
            if (context == null
                    || !(context.handler() instanceof CultDecoder decoder)
                    || !channel.isActive()
                    || connection.phase(PacketDirection.SERVERBOUND) != ConnectionPhase.PLAY) return;
            try {
                var encoded =
                        connection.packets().encode(ConnectionPhase.PLAY, type, packet, channel.alloc(), connection);
                encoded.frames().forEach(frame -> decoder.forwardInjected(context, frame));
            } catch (RuntimeException failure) {
                CultNetworkManager.reportFailure("Velocity serverbound injection", failure);
            }
        });
    }
}
