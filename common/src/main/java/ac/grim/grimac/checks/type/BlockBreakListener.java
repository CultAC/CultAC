package ac.grim.grimac.checks.type;

import ac.grim.grimac.utils.anticheat.update.BlockBreak;

public interface BlockBreakListener extends CheckListener {
    void onBlockBreak(BlockBreak blockBreak);
}
