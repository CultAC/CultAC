package ac.cult.cultac.bedrock.bridge;

import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;

/** Records keepalive timing when the native FIFO callback runs. */
final class GeyserKeepAliveTranslator extends PacketTranslator<ClientboundKeepAlivePacket> {
    private final PacketTranslator<ClientboundKeepAlivePacket> delegate;

    @SuppressWarnings("unchecked")
    GeyserKeepAliveTranslator() {
        delegate = (PacketTranslator<ClientboundKeepAlivePacket>) Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundKeepAlivePacket.class);
        if (delegate == null) throw new IllegalStateException("Missing Geyser keepalive translator");
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundKeepAlivePacket.class, this);
    }

    @Override public void translate(GeyserSession session, ClientboundKeepAlivePacket packet) {
        session.sendNetworkLatencyStackPacket(packet.getPingId(), false, () -> {
            GeyserBedrockBridgeRuntime.acceptKeepAlive(session, packet.getPingId());
            session.sendDownstreamPacket(new ServerboundKeepAlivePacket(packet.getPingId()));
        });
    }

    boolean isInstalled() { return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundKeepAlivePacket.class) == this; }

    void close() {
        if (isInstalled()) Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundKeepAlivePacket.class, delegate);
    }
}
