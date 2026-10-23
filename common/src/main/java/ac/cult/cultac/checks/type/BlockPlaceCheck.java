package ac.cult.cultac.checks.type;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.api.config.ConfigManager;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public class BlockPlaceCheck extends Check implements CheckListener {
    protected int cancelVL;

    public BlockPlaceCheck(CultPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        if (getConfigName() != null) {
            cancelVL = config.getIntElse(getConfigName() + ".cancelVL", getDefaultCancelVL());
        }
    }

    @Contract(pure = true)
    protected int getDefaultCancelVL() {
        return 5;
    }

    protected boolean shouldCancel() {
        return cancelVL >= 0 && violations >= cancelVL;
    }

    // Method called immediately after a block is placed, before forwarding block place to server
    public void onBlockPlace(final BlockPlace place) {}

    // Method called the flying packet after the block place
    public void onPostFlyingBlockPlace(BlockPlace place) {}
}
