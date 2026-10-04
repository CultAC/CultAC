package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

/** Cult reads native Velocity frames after inbound Via and before outbound Via conversion. */
final class VelocityPacketPipeline {
    private VelocityPacketPipeline() {}

    static void install(CultConnection connection) {
        install(
                connection.channel().pipeline(),
                connection.owner(),
                new CultDecoder(connection),
                new CultEncoder(connection));
    }

    static void install(ChannelPipeline pipeline, ChannelHandler decoder, ChannelHandler encoder) {
        install(pipeline, null, decoder, encoder);
    }

    private static void install(
            ChannelPipeline pipeline,
            io.netty.util.concurrent.EventExecutorGroup owner,
            ChannelHandler decoder,
            ChannelHandler encoder) {
        verifyHost(pipeline);
        pipeline.addBefore(owner, "minecraft-decoder", CultDecoder.NAME, decoder);
        pipeline.addBefore(owner, "minecraft-encoder", CultEncoder.NAME, encoder);
        verifyBefore(pipeline, "via-decoder", CultDecoder.NAME);
        verifyBefore(pipeline, "via-encoder", CultEncoder.NAME);
        verifyBefore(pipeline, "compression-decoder", CultDecoder.NAME);
        verifyBefore(pipeline, "compression-encoder", CultEncoder.NAME);
    }

    private static void verifyHost(ChannelPipeline pipeline) {
        if (pipeline.get("minecraft-decoder") == null || pipeline.get("minecraft-encoder") == null) {
            throw new IllegalStateException("Unsupported Velocity client pipeline: " + pipeline.names());
        }
        if ((pipeline.get("via-decoder") == null) != (pipeline.get("via-encoder") == null)) {
            throw new IllegalStateException("Incomplete Velocity Via codec pair: " + pipeline.names());
        }
        verifyBefore(pipeline, "via-decoder", "minecraft-decoder");
        verifyBefore(pipeline, "via-encoder", "minecraft-encoder");
        verifyBefore(pipeline, "compression-decoder", "via-decoder");
        verifyBefore(pipeline, "compression-encoder", "via-encoder");
    }

    private static void verifyBefore(ChannelPipeline pipeline, String earlier, String later) {
        if (pipeline.get(earlier) == null || pipeline.get(later) == null) return;
        var names = pipeline.names();
        if (names.indexOf(earlier) >= names.indexOf(later)) {
            throw new IllegalStateException("Expected " + earlier + " before " + later + ": " + names);
        }
    }
}
