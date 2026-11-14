package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.SimAction;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
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
    /** ServerGamePacketListenerImpl#restartClientLoadTimerAfterRespawn. */
    private static final int CLIENT_LOADED_TIMEOUT_TICKS = 60;

    private final CultPlayer player;
    private final ArrayDeque<Correction> pending = new ArrayDeque<>();
    private int lastForwarded = -1;
    private int lastConfirmedServerAck = -1;
    private @Nullable BlockPos serverDestroyPos;
    private boolean serverWaitingForRespawn;
    private boolean serverWonGame;
    private boolean serverAwaitingLoad;
    private int loadTimerStartTick;

    public BlockActionReconciler(CultPlayer player) {
        this.player = player;
    }

    /**
     * ServerGamePacketListenerImpl#hasClientLoaded is false: handleUseItemOn, handleUseItem and handlePlayerAction
     * drop the action without acknowledging its sequence. The load timer counts server ticks ended since the login or
     * respawn was sent and runs one tick past the server's, the tick the server may handle the action in.
     */
    public boolean serverDropsActions() {
        if (player.isBedrockMovement() || !player.getObservedProtocol().atLeast(ProtocolVersion.V1_21_4)) return false;
        if (serverWaitingForRespawn) return true;
        if (serverAwaitingLoad && player.getTick() - loadTimerStartTick > CLIENT_LOADED_TIMEOUT_TICKS) {
            serverAwaitingLoad = false;
        }
        return serverAwaitingLoad;
    }

    /** The ServerGamePacketListenerImpl constructor starts the load timer. */
    public void loginSent() {
        serverWaitingForRespawn = false;
        serverWonGame = false;
        startLoadTimer();
    }

    /** ServerPlayer#die sends the combat kill, then ServerGamePacketListenerImpl#markClientUnloadedAfterDeath. */
    public void deathSent() {
        serverWaitingForRespawn = true;
    }

    /** ServerPlayer#showEndCredits: the next PERFORM_RESPAWN respawns the player. */
    public void wonGameSent() {
        serverWonGame = true;
    }

    /** ServerGamePacketListenerImpl#markClientLoaded. */
    public void loadReachedServer() {
        serverAwaitingLoad = false;
    }

    /** True when this respawn is PlayerList#respawn after a death or the end credits, not a dimension change. */
    public boolean isServerRespawn() {
        return serverWaitingForRespawn || serverWonGame;
    }

    /**
     * PlayerList#respawn, sent after the respawn packet. The server has handled every action forwarded before it, so a
     * prediction no acknowledgement has ended was dropped while the player was dead; its sequence will not be
     * acknowledged. Pending corrections no longer wait for it, and in the same level the dropped predictions get the
     * server states and an acknowledgement of their sequence.
     */
    public void serverRespawned(boolean sameLevel) {
        boolean died = serverWaitingForRespawn;
        serverWaitingForRespawn = false;
        serverWonGame = false;
        serverDestroyPos = null;
        startLoadTimer();
        if (!died || player.isBedrockMovement() || CultAPI.INSTANCE.getConfigManager().isObserveOnly()) return;
        lastForwarded = Math.min(lastForwarded, lastConfirmedServerAck);
        for (Correction correction : pending) correction.barrier = Math.min(correction.barrier, lastForwarded);
        int dropped = player.compensatedWorld.highestUnacknowledgedPredictionSequence();
        if (sameLevel && dropped >= 0) {
            Correction correction = correction(dropped);
            correction.positions.addAll(player.compensatedWorld.unacknowledgedPredictionPositions());
            enqueue(correction);
        } else drain();
    }

    private void startLoadTimer() {
        serverAwaitingLoad = true;
        loadTimerStartTick = player.getTick();
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
            player.compensatedWorld.advanceClientPredictionSequence(place.sequence());
            rejectPlace(place);
        } else if (packet instanceof ServerboundPlayerAction dig && isPredictedDig(dig.action())) {
            player.compensatedWorld.advanceClientPredictionSequence(dig.sequence());
            rejectDig(dig);
        }
    }

    public void rejectPlace(ServerboundUseItemOn packet) {
        // Observe-only forwards a rejected action, so the server acknowledges it.
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            forwarded(packet.sequence());
            return;
        }
        enqueuePlace(packet, PlaceHandler.predictRejectedUseItemOn(player, packet));
    }

    /** A place whose prediction was applied before the action was withheld from the server. */
    public void rejectPlace(ServerboundUseItemOn packet, ClientBlockActions.Prediction prediction) {
        if (player.isBedrockMovement()) return;
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            forwarded(packet.sequence());
            return;
        }
        enqueuePlace(packet, prediction);
    }

    private void enqueuePlace(ServerboundUseItemOn packet, ClientBlockActions.Prediction prediction) {
        Correction correction = correction(packet.sequence());
        correction.positions.add(packet.blockPosition());
        correction.positions.add(packet.blockPosition().relative(packet.blockFace()));
        enqueue(correction, packet.hand(), prediction);
    }

    /** A use whose prediction was applied before the action was withheld from the server. */
    public void rejectUse(ServerboundUseItem packet, ClientBlockActions.Prediction prediction) {
        if (player.isBedrockMovement()) return;
        if (prediction.writes().isEmpty() && prediction.inventory().isEmpty()) return;
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            forwarded(packet.sequence());
            return;
        }
        enqueue(correction(packet.sequence()), packet.hand(), prediction);
    }

    private void enqueue(Correction correction, Hand hand, ClientBlockActions.Prediction prediction) {
        correction.slots.add(
                hand == Hand.MAIN_HAND
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
        if (serverDropsActions()) return;
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
            int menuSlot = inventory.inventory.menuSlotForStorage(slot);
            SimItemStack item = inventory.serverViewSlot(slot);
            if (menuSlot < 0 || item == null) continue;
            slots.add(new InventoryPackets.Slot(0, inventory.serverStateId(), menuSlot, item));
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
        int barrier;
        final Set<BlockPos> positions = new LinkedHashSet<>();
        final Set<Integer> slots = new LinkedHashSet<>();

        Correction(int sequence, int barrier) {
            this.sequence = sequence;
            this.barrier = barrier;
        }
    }
}
