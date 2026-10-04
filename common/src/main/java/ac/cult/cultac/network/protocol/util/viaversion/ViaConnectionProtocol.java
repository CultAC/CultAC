package ac.cult.cultac.network.protocol.util.viaversion;

import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import io.netty.channel.Channel;

/** Reads the original handshake from Via on this physical connection, never a UUID lookup. */
public final class ViaConnectionProtocol {
    private ViaConnectionProtocol() {}

    /** -1 means no translator; 0 means its original handshake is not known yet. */
    public static int originalProtocol(Channel channel) {
        var decoder = channel.pipeline().get(ViaDecodeHandler.class);
        var encoder = channel.pipeline().get(ViaEncodeHandler.class);
        if (decoder == null && encoder == null) return -1;
        if (decoder == null
                || encoder == null
                || decoder.connection() != encoder.connection()
                || decoder.connection().getChannel() != channel) {
            throw new IllegalStateException("Via codecs do not belong to this physical connection");
        }
        var original = decoder.connection().getProtocolInfo().protocolVersion();
        return original == null || original.getOriginalVersion() <= 0 ? 0 : original.getOriginalVersion();
    }
}
