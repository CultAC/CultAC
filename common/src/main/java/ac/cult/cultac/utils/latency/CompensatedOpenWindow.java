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
    private int newestServerWindow = Integer.MIN_VALUE;
    // A show_dialog that may cover a container screen, and the newest transaction written before it.
    private boolean dialogMayCover;
    private int dialogTransaction;
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
        onClientContainerScreen();
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
        if (window != null) newestServerWindow = before.transaction();
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
            closeDialogBefore(before.transaction());
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

    /**
     * LocalPlayer#handlePortalTransitionEffect sets no screen in place of a screen that is not Screen#isAllowedInPortal
     * and runs closeContainer only for an AbstractContainerScreen. A LecternScreen is a BookViewScreen: a lectern window
     * the client has handled closes; one whose open_screen it may not have handled yet stays possible. A handled
     * show_dialog may be on top of the container screen it keeps as its previous screen; DialogScreen#isPauseScreen and
     * the screens its actions open decide whether that container screen goes without a container_close.
     */
    public void onPortalClosedScreen() {
        int transaction = player.lastTransactionReceived.get();
        boolean closed = possibleOpenWindows.removeIf(
                window -> window != null && window.type() == MenuType.LECTERN && window.transaction() < transaction);
        if (!closed && !(dialogMayCover && dialogTransaction < transaction)) return;
        possibleOpenWindows.add(null);
        onClose();
        closeTransaction = transaction;
    }

    public void maybeClose() {
        possibleOpenWindows.add(null);
        closeTransaction = player.lastTransactionReceived.get();
        closeDialogBefore(player.lastTransactionReceived.get());
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

    /**
     * Minecraft#clearClientLevel, run by ClientPacketListener#handleConfigurationStart, replaces any screen with
     * ServerReconfigScreen and sends no container_close; ClientPacketListener#handleLogin then starts a new
     * LocalPlayer whose only menu is its closed inventory.
     */
    public void closeFromLogin() {
        possibleOpenWindows.clear();
        possibleOpenWindows.add(null);
        closeTransaction = player.lastTransactionReceived.get();
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
        return !possibleOpenWindows.contains(null);
    }

    /**
     * LecternScreen is a BookViewScreen: Screen#defaultHandleGameClickEvent opens a page's show_dialog over it, and
     * the dialog's run_command action reaches ClientPacketListener#sendUnattendedCommand without closing the lectern.
     */
    public boolean mayShowBookDialog() {
        for (Window window : possibleOpenWindows) {
            if (window != null && window.type() == MenuType.LECTERN) return true;
        }
        return false;
    }

    /**
     * ClientCommonPacketListenerImpl#showDialog sets the dialog in place of the current screen and keeps that screen
     * as its previous screen; a container screen it covers sends no container_close, and the dialog's run_command
     * reaches ClientPacketListener#sendUnattendedCommand.
     */
    public void onServerDialog() {
        if (!containerMayShow()) return;
        dialogMayCover = true;
        dialogTransaction = player.lastTransactionSent.get();
    }

    /** ClientCommonPacketListenerImpl#clearDialog sets the dialog's previous screen back on top. */
    public void onServerClearDialog(PacketSendEvent<?> event) {
        if (!dialogMayCover) return;
        CultPlayer.TrackedTransaction proof = player.appendTrailingProofTransaction(event);
        if (proof == null) return;
        player.latencyUtils.addRealTimeTask(proof.transaction(), () -> closeDialogBefore(proof.transaction()));
    }

    /** Only a container screen on top sends container_click and container_close. */
    public void onClientContainerScreen() {
        closeDialogBefore(player.lastTransactionReceived.get());
    }

    // The client has handled every show_dialog written before this transaction.
    private void closeDialogBefore(int transaction) {
        if (dialogTransaction < transaction) dialogMayCover = false;
    }

    public boolean mayShowServerDialog() {
        return dialogMayCover;
    }

    /**
     * Whether a container screen may be shown, or set by an open_screen the client has not handled yet. Without one, a
     * dialog never covers a container: ClientPacketListener#handleOpenScreen and #handleMountScreenOpen set the next
     * container screen in place of the dialog.
     */
    private boolean containerMayShow() {
        if (player.lastTransactionReceived.get() < newestServerWindow) return true;
        for (Window window : possibleOpenWindows) {
            if (window != null) return true;
        }
        return false;
    }

    public Set<@Nullable Window> getPossibilities() {
        return new HashSet<>(possibleOpenWindows);
    }

    public record Window(int id, long transaction, @Nullable MenuType type) {}
}
