package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.packet.InventoryPackets;
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
        sendCorrections(player, sequence, true, updates, List.of());
    }

    public static void sendCorrections(
            CultPlayer player,
            int sequence,
            boolean acknowledge,
            Collection<TrackedBlockUpdate> updates,
            Collection<InventoryPackets.Slot> slots) {
        sendCorrections(player, sequence, acknowledge, updates, slots, null);
    }

    /** {@code cursor} is the carried stack to restore, or {@code null} to leave it unchanged. */
    public static void sendCorrections(
            CultPlayer player,
            int sequence,
            boolean acknowledge,
            Collection<TrackedBlockUpdate> updates,
            Collection<InventoryPackets.Slot> slots,
            SimItemStack cursor) {
        if (updates.isEmpty() && slots.isEmpty() && cursor == null && !acknowledge) return;
        if (!player.canSendPlayPackets()) return;
        CultPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForBundle();
        if (transaction == null) {
            for (TrackedBlockUpdate update : updates) {
                player.user.write(new BlockUpdate(update.pos(), update.state()));
            }
            for (var slot : slots) player.user.write(slot);
            if (cursor != null) player.user.write(new InventoryPackets.Cursor(cursor));
            if (acknowledge) player.user.write(new CultWrite(new ClientboundBlockChangedAck(sequence), true));
            return;
        }

        List<CultWrite> packets = new ArrayList<>(updates.size() + slots.size() + 3);
        for (TrackedBlockUpdate update : updates) {
            packets.add(new CultWrite(new BlockUpdate(update.pos(), update.state()), true));
            player.compensatedWorld.handleServerBlockUpdate(update.pos(), update.state(), transaction);
        }

        for (var slot : slots) {
            packets.add(new CultWrite(slot, true));
            player.latencyUtils.addRealTimeTask(
                    transaction.transaction(), () -> player.getInventory().applySlotCorrection(slot));
        }
        if (cursor != null) {
            packets.add(new CultWrite(new InventoryPackets.Cursor(cursor), true));
            player.latencyUtils.addRealTimeTask(
                    transaction.transaction(), () -> player.getInventory().applyCursorCorrection(cursor));
        }
        if (acknowledge) {
            packets.add(new CultWrite(new ClientboundBlockChangedAck(sequence), true));
            player.compensatedWorld.handlePredictionConfirmation(sequence, transaction);
        }
        packets.add(transaction.packet());

        // Vanilla processes bundled sub-packets in order and only then replies to the ping.
        // This makes the pong an exact marker for the client applying the ack and updates.
        player.user.write(packets, true);
    }
}
