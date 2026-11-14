package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.network.CultConnection;
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
        session.sendNetworkLatencyStackPacket(id, true, latencyAnswer(session, id, ours));
    }

    /** The client's answer to ping {@code id}; {@code ours} is whether the attached player awaited it. */
    static Runnable latencyAnswer(GeyserSession session, int id, boolean ours) {
        return ours
                ? () -> GeyserBedrockBridgeRuntime.acceptTransaction(session, id)
                : () -> {
                    if (forwardsUnclaimedAnswer(GeyserBedrockBridgeRuntime.connectionForSession(session), id))
                        session.sendDownstreamPacket(new ServerboundPongPacket(id));
                };
    }

    /**
     * Whether the answer to ping {@code id}, which no attached player took, goes downstream; the session answers a
     * retired CultAC ping natively and takes it once, so only backend pings go downstream.
     */
    static boolean forwardsUnclaimedAnswer(CultConnection connection, int id) {
        return connection == null || !connection.takeRetiredPing(id);
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
