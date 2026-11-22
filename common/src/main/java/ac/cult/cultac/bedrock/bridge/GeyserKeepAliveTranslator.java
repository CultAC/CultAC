package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.integrations.GeyserTranslatorRouter;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;

/** Records keepalive timing when the native FIFO callback runs. */
final class GeyserKeepAliveTranslator extends PacketTranslator<ClientboundKeepAlivePacket> {
    private final PacketTranslator<ClientboundKeepAlivePacket> delegate;

    @SuppressWarnings("unchecked")
    GeyserKeepAliveTranslator() {
        var host = RuntimeEnvironment.host();
        if (host != null) {
            delegate = GeyserTranslatorRouter.install(ClientboundKeepAlivePacket.class, host, this);
            return;
        }
        delegate = (PacketTranslator<ClientboundKeepAlivePacket>)
                Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundKeepAlivePacket.class);
        if (delegate == null) throw new IllegalStateException("Missing Geyser keepalive translator");
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundKeepAlivePacket.class, this);
    }

    @Override
    public void translate(GeyserSession session, ClientboundKeepAlivePacket packet) {
        var downstream = session.getDownstream().getSession();
        if (downstream.getPacketProtocol().getInboundState() == ProtocolState.CONFIGURATION) {
            if (!downstream.getFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true)) {
                downstream.send(new ServerboundKeepAlivePacket(packet.getPingId()));
            }
            return;
        }
        session.sendNetworkLatencyStackPacket(packet.getPingId(), false, () -> {
            GeyserBedrockBridgeRuntime.acceptKeepAlive(session, packet.getPingId());
            session.sendDownstreamPacket(new ServerboundKeepAlivePacket(packet.getPingId()));
        });
    }

    boolean isInstalled() {
        var host = RuntimeEnvironment.host();
        if (host != null) return GeyserTranslatorRouter.installed(ClientboundKeepAlivePacket.class, host, this);
        return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundKeepAlivePacket.class) == this;
    }

    void close() {
        var host = RuntimeEnvironment.host();
        if (host != null) {
            GeyserTranslatorRouter.remove(ClientboundKeepAlivePacket.class, host, this);
            return;
        }
        if (isInstalled()) Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundKeepAlivePacket.class, delegate);
    }
}
