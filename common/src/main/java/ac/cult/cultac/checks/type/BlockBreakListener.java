package ac.cult.cultac.checks.type;

import ac.cult.cultac.utils.anticheat.update.BlockBreak;

public interface BlockBreakListener extends CheckListener {
    void onBlockBreak(BlockBreak blockBreak);
}
