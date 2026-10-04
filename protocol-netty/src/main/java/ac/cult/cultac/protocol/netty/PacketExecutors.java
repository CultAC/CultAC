package ac.cult.cultac.protocol.netty;

import ac.cult.cultac.network.CultConnection;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/** One-time Bedrock executor assignment before any configuration or player work. */
final class PacketExecutors {
    private PacketExecutors() {}

    static void rebindAndRead(ChannelHandlerContext decoder, CultConnection connection, ByteBuf frame) {
        var pipeline = decoder.pipeline();
        var names = pipeline.names();
        int index = names.indexOf(decoder.name());
        var previous = index > 0 ? pipeline.context(names.get(index - 1)) : null;
        boolean encoder = pipeline.get(CultEncoder.NAME) != null;
        pipeline.remove(CultDecoder.NAME);
        if (encoder) pipeline.remove(CultEncoder.NAME);
        CultDecoder.install(connection);
        if (encoder) CultEncoder.install(connection);
        // Netty schedules the read on the assigned executor. Resume after the
        // preceding handler so its flow-control/compression work is not repeated.
        if (previous == null) pipeline.fireChannelRead(frame);
        else previous.fireChannelRead(frame);
    }
}
