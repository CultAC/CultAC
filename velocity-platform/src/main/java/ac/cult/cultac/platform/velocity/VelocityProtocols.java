package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.protocol.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The native codec format and the original handshake belong to one physical client channel. */
final class VelocityProtocols {
    record Versions(ProtocolVersion observed, ProtocolVersion client) {}

    private VelocityProtocols() {}

    static Versions read(Player player, Channel channel) {
        var pipeline = channel.pipeline();
        ProtocolVersion decoded = codecVersion(pipeline.get("minecraft-decoder"));
        ProtocolVersion encoded = codecVersion(pipeline.get("minecraft-encoder"));
        if (decoded != encoded) {
            throw new IllegalStateException("Velocity client codec formats disagree: " + decoded + " / " + encoded);
        }
        if (player.getProtocolVersion() == null || player.getProtocolVersion().getProtocol() != decoded.protocol()) {
            throw new IllegalStateException("Velocity player protocol differs from its physical codecs");
        }
        ChannelHandler viaDecoder = pipeline.get("via-decoder");
        ChannelHandler viaEncoder = pipeline.get("via-encoder");
        if (viaDecoder == null && viaEncoder == null) return new Versions(decoded, decoded);
        if (viaDecoder == null || viaEncoder == null) {
            throw new IllegalStateException("Incomplete Velocity Via codec pair");
        }
        try {
            Object decoderConnection = invoke(viaDecoder, "connection");
            Object encoderConnection = invoke(viaEncoder, "connection");
            if (decoderConnection != encoderConnection || invoke(decoderConnection, "getChannel") != channel) {
                throw new IllegalStateException("Velocity Via codecs do not own this exact client channel");
            }
            if (!Boolean.FALSE.equals(invoke(decoderConnection, "isClientSide"))) {
                throw new IllegalStateException("Backend Via connection on a Velocity client pipeline");
            }
            Object info = invoke(decoderConnection, "getProtocolInfo");
            Object original = invoke(info, "protocolVersion");
            return new Versions(decoded, ProtocolVersion.of((int) invoke(original, "getOriginalVersion")));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported physical Velocity Via protocol identity", failure);
        }
    }

    private static ProtocolVersion codecVersion(ChannelHandler codec) {
        if (codec == null) throw new IllegalStateException("Missing Velocity Minecraft codec");
        try {
            // Velocity's MinecraftConnection.setProtocolVersion updates both registry.version fields.
            // Read the format actually used by each codec rather than a backend/server version hint.
            Field registryField = codec.getClass().getDeclaredField("registry");
            registryField.setAccessible(true);
            Object registry = registryField.get(codec);
            Field versionField = registry.getClass().getField("version");
            Object version = versionField.get(registry);
            return ProtocolVersion.of((int) invoke(version, "getProtocol"));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Velocity Minecraft codec protocol boundary", failure);
        }
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }
}
