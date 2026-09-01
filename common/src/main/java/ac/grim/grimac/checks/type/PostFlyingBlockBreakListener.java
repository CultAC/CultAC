package ac.grim.grimac.checks.type;

import ac.grim.grimac.utils.anticheat.update.BlockBreak;

public interface PostFlyingBlockBreakListener extends CheckListener {
    void onPostFlyingBlockBreak(BlockBreak blockBreak);
}
