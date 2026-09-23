package ac.cult.cultac.bedrock.bridge;

import java.util.function.Predicate;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundBlockChangedAckPacket;

/** Brackets only WorldCache.endPredictionsUpTo's synchronous, client-only block corrections. */
final class GeyserBlockAckTranslator extends PacketTranslator<ClientboundBlockChangedAckPacket> {
    private final PacketTranslator<ClientboundBlockChangedAckPacket> delegate;
    private final Predicate<GeyserSession> attached;

    @SuppressWarnings("unchecked")
    GeyserBlockAckTranslator(Predicate<GeyserSession> attached) {
        this.attached = attached;
        delegate = (PacketTranslator<ClientboundBlockChangedAckPacket>) Registries.JAVA_PACKET_TRANSLATORS
                .get(ClientboundBlockChangedAckPacket.class);
        if (delegate == null) throw new IllegalStateException("Missing Geyser block acknowledgement translator");
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundBlockChangedAckPacket.class, this);
    }

    @Override public void translate(GeyserSession session, ClientboundBlockChangedAckPacket packet) {
        if (!attached.test(session)) { delegate.translate(session, packet); return; }
        bracket(session, () -> delegate.translate(session, packet));
    }

    static void bracket(GeyserSession session, Runnable translation) {
        // Send through the same FIFO as the corrections. A flag around translate() would
        // already be cleared when Cloudburst drains its asynchronous packet queue.
        session.sendUpstreamPacket(new Boundary(true));
        try { translation.run(); }
        finally { session.sendUpstreamPacket(new Boundary(false)); }
    }

    boolean isInstalled() {
        return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundBlockChangedAckPacket.class) == this;
    }

    void close() {
        if (isInstalled()) Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundBlockChangedAckPacket.class, delegate);
    }

    /** Consumed by Cult's outbound handler, never sent to the client. */
    static final class Boundary extends NetworkStackLatencyPacket {
        final boolean start;
        Boundary(boolean start) { this.start = start; }
    }
}
