package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;

@CheckData(
        name = "MultiActionsL",
        stableKey = "cult.multiactions.inventory_break",
        description = "Breaking a block while in an inventory")
public class MultiActionsL extends Check implements BlockBreakListener {
    // MultiPlayerGameMode#destroyBlockPos while isDestroying, null otherwise.
    private BlockPos destroying;

    public MultiActionsL(CultPlayer player) {
        super(player);
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        // MultiPlayerGameMode#stopDestroyBlock aborts the block being destroyed with DOWN; from 26.3
        // Minecraft#pauseGame and ClientPacketListener#handleMovePlayer call it with a screen open.
        boolean stopsDestroy = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                && blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK
                && blockBreak.face == Direction.DOWN
                && blockBreak.position.equals(destroying);
        trackDestroy(blockBreak);

        if (!player.openWindow.mustBeOpen()) return;

        if (blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK
                && (player.openWindow.getTicksOpen() == 0 || stopsDestroy)) {
            return;
        }

        if (flag() && shouldModifyPackets()) {
            blockBreak.cancel();
            player.closeInventorySafely();
        }
    }

    // MultiPlayerGameMode#startDestroyBlock leaves the state alone with instabuild, destroys at once a block it breaks
    // in one tick, and destroys any other over the ticks until STOP_DESTROY_BLOCK or ABORT_DESTROY_BLOCK.
    private void trackDestroy(BlockBreak blockBreak) {
        switch (blockBreak.action) {
            case START_DESTROY_BLOCK -> {
                if (player.canInstabuild) return;
                destroying =
                        BlockBreakSpeed.getBlockDamage(player, blockBreak.block) >= 1.0 ? null : blockBreak.position;
            }
            case STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> destroying = null;
            default -> {}
        }
    }
}
