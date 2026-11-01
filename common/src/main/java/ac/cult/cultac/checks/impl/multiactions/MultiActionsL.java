package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;

@CheckData(
        name = "MultiActionsL",
        stableKey = "cult.multiactions.inventory_break",
        description = "Breaking a block while in an inventory")
public class MultiActionsL extends Check implements BlockBreakListener {

    public MultiActionsL(CultPlayer player) {
        super(player);
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        if (!player.openWindow.mustBeOpen()) return;

        if (blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK && player.openWindow.getTicksOpen() == 0) {
            return;
        }

        if (flag() && shouldModifyPackets()) {
            blockBreak.cancel();
            player.closeInventorySafely();
        }
    }
}
