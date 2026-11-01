package ac.cult.cultac.utils.latency;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.events.packets.patch.ResyncWorldUtil;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.inventory.InventoryClick;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.ObjLongConsumer;

/**
 * Debug stimulus for the resync paths: drops the client's interactions before Cult or the server
 * sees them, so the client's local predictions desynchronize, then resyncs from the server views.
 *
 * <p>Dropped block actions leave the client predicting until an acknowledgement reaches their
 * sequence (BlockStatePredictionHandler keeps predicted states until then), so the resync ends with
 * an acknowledgement of the highest dropped sequence. Connection-owner only.
 */
public final class InteractionPause {
    private static final int MAX_TRACKED_POSITIONS = 512;

    private final CultPlayer player;
    private final ObjLongConsumer<Runnable> delay;
    private final Set<BlockPos> positions = new LinkedHashSet<>();
    private boolean active;
    private int generation;
    private int highestSequence = -1;
    private int dropped;

    public InteractionPause(CultPlayer player) {
        this(
                player,
                (task, millis) -> CultAPI.INSTANCE
                        .getScheduler()
                        .getAsyncScheduler()
                        .runDelayed(CultAPI.INSTANCE.getGrimPlugin(), task, millis, TimeUnit.MILLISECONDS));
    }

    /** {@code delay} runs a task after the given milliseconds on any thread. */
    public InteractionPause(CultPlayer player, ObjLongConsumer<Runnable> delay) {
        this.player = player;
        this.delay = delay;
    }

    public boolean isActive() {
        return active;
    }

    /** Drops interactions for {@code millis}, then resyncs. A non-positive duration resyncs now. */
    public void start(long millis) {
        if (millis <= 0) {
            if (active) resync();
            return;
        }
        if (!active) {
            positions.clear();
            highestSequence = -1;
            dropped = 0;
        }
        active = true;
        int scheduled = ++generation;
        delay.accept(
                () -> player.runSafely(() -> {
                    if (active && generation == scheduled) resync();
                }),
                millis);
    }

    /** Called before dispatch on the connection owner; true discards the packet. */
    public boolean discards(Object packet) {
        if (!active) return false;
        switch (packet) {
            case ServerboundUseItemOn place -> {
                predicted(place.sequence());
                track(place.blockPosition());
                track(place.blockPosition().relative(place.blockFace()));
            }
            case ServerboundUseItem use -> predicted(use.sequence());
            case ServerboundPlayerAction action
            when isBlockAction(action.action()) -> {
                // MultiPlayerGameMode predicts only starting and finishing a break.
                if (action.action() == PlayerAction.START_DESTROY_BLOCK
                        || action.action() == PlayerAction.STOP_DESTROY_BLOCK) predicted(action.sequence());
                else highestSequence = Math.max(highestSequence, action.sequence());
                track(action.position());
            }
            case ServerboundInteract ignored -> {}
            case InventoryClick ignored -> {}
            case InventoryPackets.CreativeSlot ignored -> {}
            default -> {
                return false;
            }
        }
        dropped++;
        return true;
    }

    private void predicted(int sequence) {
        // Keep Cult's prediction counter aligned with the client's, as for any rejected action.
        player.compensatedWorld.advanceClientPredictionSequence();
        highestSequence = Math.max(highestSequence, sequence);
    }

    private void track(BlockPos pos) {
        if (positions.size() >= MAX_TRACKED_POSITIONS) return;
        // A placement or break can also change its neighbours (doors, beds, connected shapes).
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) positions.add(pos.offset(x, y, z));
    }

    private static boolean isBlockAction(PlayerAction action) {
        return action == PlayerAction.START_DESTROY_BLOCK
                || action == PlayerAction.CHANGE_DESTROY_DIRECTION
                || action == PlayerAction.ABORT_DESTROY_BLOCK
                || action == PlayerAction.STOP_DESTROY_BLOCK;
    }

    private void resync() {
        active = false;
        generation++;
        boolean acknowledges = highestSequence >= 0;
        int blocks = ResyncWorldUtil.sendServerStates(player, positions, acknowledges ? highestSequence : -1);
        int slots = player.getInventory().resyncFromServerView();
        LogUtil.info("Cult interaction resync player=" + player.getName()
                + " dropped=" + dropped
                + " blocks=" + blocks
                + " slots=" + slots
                + " ack=" + (acknowledges ? highestSequence : -1));
        positions.clear();
        highestSequence = -1;
        dropped = 0;
    }
}
