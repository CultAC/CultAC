package ac.cult.cultac.bedrock.bridge;

import java.util.function.Predicate;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundBlockChangedAckPacket;

/**
 * Brackets the client-only block corrections Geyser sends synchronously: WorldCache.endPredictionsUpTo's for a Java
 * acknowledgement, and BlockUtils.restoreCorrectBlock's for a block use or break Geyser rejects without asking Java.
 */
final class GeyserBlockAckTranslator extends PacketTranslator<ClientboundBlockChangedAckPacket> {
    private final PacketTranslator<ClientboundBlockChangedAckPacket> delegate;
    private final Predicate<GeyserSession> attached;

    @SuppressWarnings("unchecked")
    GeyserBlockAckTranslator(Predicate<GeyserSession> attached) {
        this.attached = attached;
        var host = ac.cult.cultac.runtime.RuntimeEnvironment.host();
        if (host != null) {
            delegate = ac.cult.reload.integrations.GeyserTranslatorRouter.install(
                    ClientboundBlockChangedAckPacket.class, host, this);
            return;
        }
        delegate = (PacketTranslator<ClientboundBlockChangedAckPacket>)
                Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundBlockChangedAckPacket.class);
        if (delegate == null) throw new IllegalStateException("Missing Geyser block acknowledgement translator");
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundBlockChangedAckPacket.class, this);
    }

    @Override
    public void translate(GeyserSession session, ClientboundBlockChangedAckPacket packet) {
        if (!attached.test(session)) {
            delegate.translate(session, packet);
            return;
        }
        bracket(session, () -> delegate.translate(session, packet));
    }

    /**
     * Whether Geyser may answer this client packet with BlockUtils.restoreCorrectBlock: a block use it rejects in
     * BedrockInventoryTransactionTranslator (click off the block, out of range, wrong held item, world border) or a
     * break BlockBreakHandler rejects from an auth input. The client's own prediction may already have changed the
     * block, and no Java update follows the restore when the server's block never changed.
     */
    static boolean mayRestoreBlocks(BedrockPacket packet) {
        return packet instanceof PlayerAuthInputPacket
                || packet instanceof InventoryTransactionPacket use
                        && use.getTransactionType() == InventoryTransactionType.ITEM_USE
                        && use.getActionType() == 0;
    }

    static void bracket(GeyserSession session, Runnable translation) {
        // Send through the same FIFO as the corrections. A flag around translate() would
        // already be cleared when Cloudburst drains its asynchronous packet queue.
        session.sendUpstreamPacket(new Boundary(true));
        try {
            translation.run();
        } finally {
            session.sendUpstreamPacket(new Boundary(false));
        }
    }

    boolean isInstalled() {
        var host = ac.cult.cultac.runtime.RuntimeEnvironment.host();
        if (host != null)
            return ac.cult.reload.integrations.GeyserTranslatorRouter.installed(
                    ClientboundBlockChangedAckPacket.class, host, this);
        return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundBlockChangedAckPacket.class) == this;
    }

    void close() {
        var host = ac.cult.cultac.runtime.RuntimeEnvironment.host();
        if (host != null) {
            ac.cult.reload.integrations.GeyserTranslatorRouter.remove(
                    ClientboundBlockChangedAckPacket.class, host, this);
            return;
        }
        if (isInstalled())
            Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundBlockChangedAckPacket.class, delegate);
    }

    /** Consumed by Cult's outbound handler, never sent to the client. */
    static final class Boundary extends NetworkStackLatencyPacket {
        final boolean start;

        Boundary(boolean start) {
            this.start = start;
        }
    }
}
