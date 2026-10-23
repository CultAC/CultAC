package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;

@CheckData(
        name = "MultiActionsB",
        stableKey = "cult.multiactions.break_while_using",
        description = "Breaking blocks while using an item",
        experimental = true)
public class MultiActionsB extends Check implements BlockBreakListener {
    public MultiActionsB(CultPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (MultiActionsA.isActivelyUsingItem(player)) {
            if (flag() && shouldModifyPackets()) {
                blockBreak.cancel();
            }
        }
    }
}
