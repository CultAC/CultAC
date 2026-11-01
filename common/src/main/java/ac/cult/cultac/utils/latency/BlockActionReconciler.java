package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.SimAction;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Reject client actions without admitting their writes to the simulation world. Owner-thread only. */
public final class BlockActionReconciler {
    private final CultPlayer player;
    private final ArrayDeque<Correction> pending = new ArrayDeque<>();
    private int lastForwarded = -1;
    private int lastConfirmedServerAck = -1;
    private @Nullable BlockPos serverDestroyPos;

    public BlockActionReconciler(CultPlayer player) {
        this.player = player;
    }

    public void forwarded(int sequence) {
        if (!player.isBedrockMovement()) lastForwarded = Math.max(lastForwarded, sequence);
    }

    /** Called after the backend acknowledgement and its preceding updates are client-confirmed. */
    public void confirmServerAck(int sequence) {
        lastConfirmedServerAck = Math.max(lastConfirmedServerAck, sequence);
        drain();
    }

    public void rejectEarly(Object packet) {
        if (player.isBedrockMovement()) return;
        if (packet instanceof ServerboundUseItemOn place) {
            player.compensatedWorld.advanceClientPredictionSequence();
            rejectPlace(place);
        } else if (packet instanceof ServerboundPlayerAction dig && isPredictedDig(dig.action())) {
            player.compensatedWorld.advanceClientPredictionSequence();
            rejectDig(dig);
        }
    }

    public void rejectPlace(ServerboundUseItemOn packet) {
        // Observe-only forwards a rejected action, so the server acknowledges it.
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            forwarded(packet.sequence());
            return;
        }
        var prediction = PlaceHandler.predictRejectedUseItemOn(player, packet);
        Correction correction = correction(packet.sequence());
        correction.positions.add(packet.blockPosition());
        correction.positions.add(packet.blockPosition().relative(packet.blockFace()));
        correction.slots.add(
                packet.hand() == Hand.MAIN_HAND
                        ? Inventory.HOTBAR_OFFSET + player.packetStateData.lastSlotSelected
                        : Inventory.SLOT_OFFHAND);
        trackWrites(correction, prediction);
        prediction
                .inventory()
                .keySet()
                .forEach(slot -> correction.slots.add(BlockSimulatorInventory.storageSlot(slot)));
        enqueue(correction);
    }

    /**
     * Tracks ServerPlayerGameMode#destroyPos: a START_DESTROY_BLOCK sets it, a STOP_DESTROY_BLOCK at that position or
     * any ABORT_DESTROY_BLOCK ends the session.
     */
    public void digReachedServer(ServerboundPlayerAction packet) {
        switch (packet.action()) {
            case START_DESTROY_BLOCK -> serverDestroyPos = packet.position();
            case STOP_DESTROY_BLOCK -> {
                if (packet.position().equals(serverDestroyPos)) serverDestroyPos = null;
            }
            case ABORT_DESTROY_BLOCK -> serverDestroyPos = null;
            default -> {}
        }
    }

    /**
     * ServerPlayerGameMode#tick sends level events 2019 and 2020 for the destroy session each tick, and only a STOP at
     * ServerPlayerGameMode#destroyPos or an ABORT ends it. A STOP the server must not receive at that position is sent
     * as an ABORT with its sequence, which ends the session without destroying the block and acknowledges the sequence.
     */
    public void abortServerDestroy(PacketReceiveEvent<ServerboundPlayerAction> event, ServerboundPlayerAction packet) {
        if (player.isBedrockMovement() || CultAPI.INSTANCE.getConfigManager().isObserveOnly()) return;
        if (packet.action() != PlayerAction.STOP_DESTROY_BLOCK
                || !packet.position().equals(serverDestroyPos)) return;
        event.setCancelled(false);
        event.replace(new ServerboundPlayerAction(
                PlayerAction.ABORT_DESTROY_BLOCK, packet.position(), packet.direction(), packet.sequence()));
    }

    public void rejectDig(ServerboundPlayerAction packet) {
        if (player.isBedrockMovement()) return;
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            forwarded(packet.sequence());
            return;
        }
        Correction correction = correction(packet.sequence());
        correction.positions.add(packet.position());
        if (packet.action() == PlayerAction.STOP_DESTROY_BLOCK
                || BlockBreakSpeed.getBlockDamage(player, packet.position()) >= 1) {
            var pos = packet.position();
            trackWrites(
                    correction,
                    ClientBlockActions.predict(
                            player,
                            new SimAction.DestroyBlock(new ac.cult.blocksim.engine.BlockPos(pos.x(), pos.y(), pos.z())),
                            player.xRot,
                            player.yRot));
        }
        enqueue(correction);
    }

    private void trackWrites(Correction correction, ClientBlockActions.Prediction prediction) {
        for (var write : prediction.writes()) {
            correction.positions.add(write.getKey());
        }
    }

    private Correction correction(int sequence) {
        return new Correction(sequence, lastForwarded);
    }

    private void enqueue(Correction correction) {
        // Consecutive rejected actions share one backend barrier and one cumulative acknowledgement.
        Correction previous = pending.peekLast();
        if (previous != null && previous.barrier == correction.barrier) {
            previous.sequence = Math.max(previous.sequence, correction.sequence);
            previous.positions.addAll(correction.positions);
            previous.slots.addAll(correction.slots);
        } else pending.addLast(correction);
        drain();
    }

    private void drain() {
        while (!pending.isEmpty() && pending.getFirst().barrier <= lastConfirmedServerAck) {
            send(pending.removeFirst());
        }
    }

    private void send(Correction correction) {
        var world = player.compensatedWorld;
        var inventory = player.getInventory();
        var updates = new ArrayList<BlockPredictionAckSender.TrackedBlockUpdate>();
        for (BlockPos pos : correction.positions) {
            // Later server updates may still be in flight; this correction is written after them.
            int state = world.getServerSentBlockStateIdAt(pos);
            if (state == CompensatedWorld.UNKNOWN_SERVER_STATE) continue;
            updates.add(new BlockPredictionAckSender.TrackedBlockUpdate(pos, state));
        }
        var slots = new ArrayList<InventoryPackets.Slot>();
        for (int slot : correction.slots) {
            slots.add(new InventoryPackets.Slot(
                    0, inventory.serverStateId(), slot, inventory.serverSlotForCorrection(slot)));
        }
        BlockPredictionAckSender.sendCorrections(player, correction.sequence, true, updates, slots);
    }

    public void reset() {
        pending.clear();
        lastForwarded = -1;
        lastConfirmedServerAck = -1;
        serverDestroyPos = null;
    }

    private static boolean isPredictedDig(PlayerAction action) {
        return action == PlayerAction.START_DESTROY_BLOCK || action == PlayerAction.STOP_DESTROY_BLOCK;
    }

    private static final class Correction {
        int sequence;
        final int barrier;
        final Set<BlockPos> positions = new LinkedHashSet<>();
        final Set<Integer> slots = new LinkedHashSet<>();

        Correction(int sequence, int barrier) {
            this.sequence = sequence;
            this.barrier = barrier;
        }
    }
}
