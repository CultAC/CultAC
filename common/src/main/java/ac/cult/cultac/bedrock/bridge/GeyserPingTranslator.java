package ac.cult.cultac.bedrock.bridge;

import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;

/** Completes Cult's transaction on the native latency response, before the next input. */
final class GeyserPingTranslator extends PacketTranslator<ClientboundPingPacket> {
    private final PacketTranslator<ClientboundPingPacket> delegate;

    @SuppressWarnings("unchecked")
    GeyserPingTranslator() {
        delegate = (PacketTranslator<ClientboundPingPacket>) Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundPingPacket.class);
        if (delegate == null) throw new IllegalStateException("Missing Geyser ping translator");
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundPingPacket.class, this);
    }

    @Override public void translate(GeyserSession session, ClientboundPingPacket packet) {
        session.sendNetworkLatencyStackPacket(packet.getId(), true, () -> {
            if (!GeyserBedrockBridgeRuntime.acceptTransaction(session, packet.getId())) {
                session.sendDownstreamPacket(new ServerboundPongPacket(packet.getId()));
            }
        });
    }

    boolean isInstalled() { return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundPingPacket.class) == this; }

    void close() {
        if (isInstalled()) Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundPingPacket.class, delegate);
    }
}
