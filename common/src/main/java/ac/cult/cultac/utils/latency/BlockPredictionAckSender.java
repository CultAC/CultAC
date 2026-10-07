package ac.cult.cultac.utils.latency;

import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.packet.WorldPackets.BlockUpdate;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.value.BlockPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class BlockPredictionAckSender {
    private BlockPredictionAckSender() {}

    public record TrackedBlockUpdate(BlockPos pos, int state) {}

    public static void sendAck(CultPlayer player, int sequence) {
        sendAck(player, sequence, List.of());
    }

    public static void sendAck(CultPlayer player, int sequence, Collection<TrackedBlockUpdate> updates) {
        CultPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForBundle();
        if (transaction == null) {
            for (TrackedBlockUpdate update : updates) {
                player.user.write(new BlockUpdate(update.pos(), update.state()));
            }
            player.user.write(new CultWrite(new ClientboundBlockChangedAck(sequence), false));
            return;
        }

        List<CultWrite> packets = new ArrayList<>(updates.size() + 2);
        for (TrackedBlockUpdate update : updates) {
            packets.add(new CultWrite(new BlockUpdate(update.pos(), update.state()), true));
            player.compensatedWorld.handleServerBlockUpdate(update.pos(), update.state(), transaction);
        }

        packets.add(new CultWrite(new ClientboundBlockChangedAck(sequence), true));
        packets.add(transaction.packet());
        player.compensatedWorld.handlePredictionConfirmation(sequence, transaction);

        // Vanilla processes bundled sub-packets in order and only then replies to the ping.
        // This makes the pong an exact marker for the client applying the ack and updates.
        player.user.write(packets, true);
    }
}
