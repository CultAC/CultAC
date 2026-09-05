package ac.cult.cultac.checks.type;

import ac.cult.cultac.utils.anticheat.update.BlockBreak;

public interface PostFlyingBlockBreakListener extends CheckListener {
    void onPostFlyingBlockBreak(BlockBreak blockBreak);
}
