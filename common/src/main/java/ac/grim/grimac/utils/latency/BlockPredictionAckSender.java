package ac.grim.grimac.utils.latency;

import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class BlockPredictionAckSender {
    private BlockPredictionAckSender() {
    }

    public record TrackedBlockUpdate(BlockPos pos, BlockState state) {
    }

    public static void sendAck(GrimPlayer player, int sequence) {
        sendAck(player, sequence, List.of());
    }

    public static void sendAck(GrimPlayer player, int sequence, Collection<TrackedBlockUpdate> updates) {
        GrimPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForBundle();
        if (transaction == null) {
            for (TrackedBlockUpdate update : updates) {
                player.user.sendPacket(new ClientboundBlockUpdatePacket(update.pos(), update.state()));
            }
            player.user.sendPacket(new ClientboundBlockChangedAckPacket(sequence));
            return;
        }

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(updates.size() + 2);
        List<Packet<?>> alreadyHandledPackets = new ArrayList<>(updates.size() + 1);
        for (TrackedBlockUpdate update : updates) {
            ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(update.pos(), update.state());
            packets.add(packet);
            alreadyHandledPackets.add(packet);
            player.compensatedWorld.handleServerBlockUpdate(update.pos(), update.state(), transaction);
        }

        ClientboundBlockChangedAckPacket ack = new ClientboundBlockChangedAckPacket(sequence);
        packets.add(ack);
        alreadyHandledPackets.add(ack);
        packets.add(transaction.packet());
        player.compensatedWorld.handlePredictionConfirmation(sequence, transaction);

        // Vanilla processes bundled sub-packets in order and only then replies to the ping.
        // This makes the pong an exact marker for the client applying the ack and updates.
        player.user.sendPacketWithSilentPackets(new ClientboundBundlePacket(packets), alreadyHandledPackets);
    }
}
