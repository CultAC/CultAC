package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

/** Split-state window tracking ported from Grim PR #2848 by ManInMyVan. */
public final class CompensatedOpenWindow implements CheckListener, ClientTickEndListener {
    private static final Window PLAYER_INVENTORY = new Window(0, Long.MIN_VALUE, null);
    private final CultPlayer player;
    private final Set<@Nullable Window> possibleOpenWindows = new HashSet<>(2);
    private long openPlayerInventoryTransaction = Long.MIN_VALUE;
    private long closeTransaction = Long.MIN_VALUE;
    // An acknowledged open_sign_editor whose editor has not yet sent its sign_update.
    private boolean signEditorOpen;
    private int signEditorTransaction;

    @Getter
    private int ticksOpen;

    public CompensatedOpenWindow(CultPlayer player) {
        this.player = player;
        possibleOpenWindows.add(null);
    }

    public void onClientOpenOrCloseWindow(boolean open) {
        int transaction = player.lastTransactionReceived.get();
        possibleOpenWindows.removeIf(
                window -> window == null || window == PLAYER_INVENTORY || window.transaction < transaction);
        possibleOpenWindows.add(open ? PLAYER_INVENTORY : null);
        if (open) {
            openPlayerInventoryTransaction = transaction;
        } else {
            onClose();
            closeTransaction = transaction;
        }
    }

    public void onServerWindow(PacketSendEvent<?> event, int id, @Nullable MenuType type) {
        CultPlayer.TrackedTransaction before =
                player.createTrackedTransactionPacketForDeferredSend(CultPlayer.PING_LEADING);
        CultPlayer.TrackedTransaction after = player.createTrackedTransactionPacketForDeferredSend();
        if (before == null || after == null) return;
        Window window = type == null ? null : new Window(id, before.transaction(), type);
        // Both proofs stay inside the outbound group. Vanilla processes bundles
        // before a client tick, so no ping is inserted midway through a bundle.
        event.getWritesBeforeSend().add(before.packet());
        event.getWritesAfterSend().add(after.packet());
        event.receiptProof(after.transaction());
        event.getTasksAfterSend().add(() -> {
            player.markTrackedTransactionPacketSent(before);
            player.markTrackedTransactionPacketSent(after);
        });
        player.latencyUtils.addRealTimeTask(before.transaction(), () -> possibleOpenWindows.add(window));
        player.latencyUtils.addRealTimeTask(after.transaction(), () -> {
            // A vanilla editor sends its sign_update between these proofs when this window replaces it.
            if (signEditorTransaction < before.transaction()) signEditorOpen = false;
            closeBefore(before.transaction(), window != null);
            if (window != null) player.serverOpenedInventoryThisTick = true;
        });
    }

    private void closeBefore(int transaction, boolean closeEmpty) {
        if (!closeEmpty) {
            closeTransaction = player.lastTransactionReceived.get();
            onClose();
        }
        boolean shouldCloseEmpty = closeEmpty && closeTransaction <= transaction;
        boolean shouldClosePlayer = openPlayerInventoryTransaction <= transaction;
        possibleOpenWindows.removeIf(window -> window == PLAYER_INVENTORY
                ? shouldClosePlayer
                : window == null ? shouldCloseEmpty : window.transaction < transaction);
    }

    /**
     * ClientPacketListener#handleOpenSignEditor sets the sign's edit screen in place of any container screen, whose
     * AbstractContainerScreen#removed sends no container_close; AbstractSignEditScreen#removed sends the sign_update
     * when the editor leaves the screen.
     */
    public void onServerSignEditor(PacketSendEvent<?> event) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_11)) return;
        CultPlayer.TrackedTransaction proof = player.appendTrailingProofTransaction(event);
        if (proof == null) return;
        player.latencyUtils.addRealTimeTask(proof.transaction(), () -> {
            signEditorOpen = true;
            signEditorTransaction = proof.transaction();
        });
    }

    public void onClientSignUpdate() {
        if (!signEditorOpen) return;
        signEditorOpen = false;
        onClientOpenOrCloseWindow(false);
    }

    public void maybeClose() {
        possibleOpenWindows.add(null);
        closeTransaction = player.lastTransactionReceived.get();
        onClose();
    }

    public void closeFromRespawn() {
        int transaction = player.lastTransactionReceived.get() - 1;
        possibleOpenWindows.removeIf(window -> window == null
                || window == PLAYER_INVENTORY && openPlayerInventoryTransaction == transaction
                || window.transaction < transaction);
        possibleOpenWindows.add(null);
        signEditorOpen = false;
        onClose();
    }

    // A closed screen lets the client change its selected slot again before its next tick.
    private void onClose() {
        ticksOpen = 0;
        player.packetStateData.heldSlotSetWithScreenOpen = false;
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (!player.isBedrockMovement()) onClientTick();
    }

    public void onClientTick() {
        ticksOpen = mustBeOpen() ? ticksOpen + 1 : 0;
    }

    public boolean mustBeOpen() {
        for (Window window : possibleOpenWindows) {
            if (window == null) return false;
        }
        return true;
    }

    public Set<@Nullable Window> getPossibilities() {
        return new HashSet<>(possibleOpenWindows);
    }

    public record Window(int id, long transaction, @Nullable MenuType type) {}
}
