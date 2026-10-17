package ac.cult.cultac.utils.latency;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

/** Split-state window tracking ported from Grim PR #2848 by ManInMyVan. */
public final class CompensatedOpenWindow
        implements ac.cult.cultac.checks.type.CheckListener, ac.cult.cultac.checks.type.ClientTickEndListener {
    private static final Window PLAYER_INVENTORY = new Window(0, Long.MIN_VALUE, null);
    private final CultPlayer player;
    private final Set<@Nullable Window> possibleOpenWindows = new HashSet<>(2);
    private long openPlayerInventoryTransaction = Long.MIN_VALUE;
    private long closeTransaction = Long.MIN_VALUE;
    public final boolean clientSendsOpenInventoryPacket;

    @Getter
    private int ticksOpen;

    public CompensatedOpenWindow(CultPlayer player) {
        this.player = player;
        clientSendsOpenInventoryPacket =
                player.getClientVersion().isOlderThan(ac.cult.cultac.network.protocol.ClientVersion.V_1_12);
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
            ticksOpen = 0;
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
        event.getTasksAfterSend().add(() -> {
            player.markTrackedTransactionPacketSent(before);
            player.markTrackedTransactionPacketSent(after);
        });
        player.latencyUtils.addRealTimeTask(before.transaction(), () -> possibleOpenWindows.add(window));
        player.latencyUtils.addRealTimeTask(after.transaction(), () -> {
            closeBefore(before.transaction(), window != null);
            if (window != null) player.serverOpenedInventoryThisTick = true;
        });
    }

    private void closeBefore(int transaction, boolean closeEmpty) {
        if (!closeEmpty) {
            closeTransaction = player.lastTransactionReceived.get();
            ticksOpen = 0;
        }
        boolean shouldCloseEmpty = closeEmpty && closeTransaction <= transaction;
        boolean shouldClosePlayer = openPlayerInventoryTransaction <= transaction;
        possibleOpenWindows.removeIf(window -> window == PLAYER_INVENTORY
                ? shouldClosePlayer
                : window == null ? shouldCloseEmpty : window.transaction < transaction);
    }

    public void maybeClose() {
        possibleOpenWindows.add(null);
        closeTransaction = player.lastTransactionReceived.get();
        ticksOpen = 0;
    }

    public void closeFromRespawn() {
        int transaction = player.lastTransactionReceived.get() - 1;
        possibleOpenWindows.removeIf(window -> window == null
                || window == PLAYER_INVENTORY && openPlayerInventoryTransaction == transaction
                || window.transaction < transaction);
        possibleOpenWindows.add(null);
        ticksOpen = 0;
    }

    @Override
    public void onPlayerTickEnd(ac.cult.cultac.network.event.PacketReceiveEvent event) {
        if (!player.isBedrockMovement()) onClientTick();
    }

    @ac.cult.cultac.network.CultPacketHandler
    public void onMovePlayer(
            ac.cult.cultac.network.event.PacketReceiveEvent<
                            ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer>
                    event,
            CultPlayer player,
            ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer packet) {
        if (!player.isBedrockMovement()
                && !player.supportsEndTick()
                && !player.packetStateData.lastPacketWasTeleport
                && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) onClientTick();
    }

    public void onClientTick() {
        ticksOpen = mustBeOpen() ? ticksOpen + 1 : 0;
    }

    public boolean mustBeOpen() {
        for (Window window : possibleOpenWindows) {
            if (window == null || window.canDesync(player)) return false;
        }
        return true;
    }

    public Set<@Nullable Window> getPossibilities() {
        return new HashSet<>(possibleOpenWindows);
    }

    public record Window(int id, long transaction, @Nullable MenuType type) {
        public boolean canDesync(CultPlayer player) {
            return player.getClientVersion().isOlderThanOrEquals(ac.cult.cultac.network.protocol.ClientVersion.V_1_8)
                    && type == MenuType.BEACON;
        }
    }
}
