package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.player.CultPlayer;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;

/** Completes Cult's transaction on the native latency response, before the next input. */
final class GeyserPingTranslator extends PacketTranslator<ClientboundPingPacket> {
    private final PacketTranslator<ClientboundPingPacket> delegate;

    @SuppressWarnings("unchecked")
    GeyserPingTranslator() {
        var host = ac.cult.cultac.runtime.RuntimeEnvironment.host();
        if (host != null) {
            delegate =
                    ac.cult.reload.integrations.GeyserTranslatorRouter.install(ClientboundPingPacket.class, host, this);
            return;
        }
        delegate = (PacketTranslator<ClientboundPingPacket>)
                Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundPingPacket.class);
        if (delegate == null) throw new IllegalStateException("Missing Geyser ping translator");
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundPingPacket.class, this);
    }

    @Override
    public void translate(GeyserSession session, ClientboundPingPacket packet) {
        var downstream = session.getDownstream().getSession();
        if (downstream.getPacketProtocol().getInboundState() == ProtocolState.CONFIGURATION) {
            downstream.send(new ServerboundPongPacket(packet.getId()));
            return;
        }
        int id = packet.getId();
        CultPlayer player = GeyserBedrockBridgeRuntime.playerForSession(session);
        boolean ours = player != null && player.awaitsTransactionResponse(id);
        session.sendNetworkLatencyStackPacket(id, true, ours
                ? () -> GeyserBedrockBridgeRuntime.acceptTransaction(session, id)
                : () -> session.sendDownstreamPacket(new ServerboundPongPacket(id)));
    }

    boolean isInstalled() {
        var host = ac.cult.cultac.runtime.RuntimeEnvironment.host();
        if (host != null)
            return ac.cult.reload.integrations.GeyserTranslatorRouter.installed(
                    ClientboundPingPacket.class, host, this);
        return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundPingPacket.class) == this;
    }

    void close() {
        var host = ac.cult.cultac.runtime.RuntimeEnvironment.host();
        if (host != null) {
            ac.cult.reload.integrations.GeyserTranslatorRouter.remove(ClientboundPingPacket.class, host, this);
            return;
        }
        if (isInstalled()) Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundPingPacket.class, delegate);
    }
}
