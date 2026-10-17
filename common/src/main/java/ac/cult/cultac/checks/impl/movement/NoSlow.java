package ac.cult.cultac.checks.impl.movement;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.nmsutil.IsUsingItem;
import ac.grim.grimac.api.config.ConfigManager;
import org.jetbrains.annotations.NotNull;

@CheckData(
        name = "NoSlow",
        stableKey = "cult.movement.noslow",
        description = "Was not slowed while using an item",
        setback = 5)
@DeadCheck(
        reason = DeadCheck.Reason.DEAD_BY_CONSTRUCTION,
        detail =
                "Deliberately never registered: its offset feed died with the 2.0 runner; superseded by prediction/checks/NoSlow + ItemUseDesync.")
public class NoSlow extends Check implements PostPredictionListener {
    public boolean flaggedLastTick = false;
    private double offsetToFlag;
    private double bestOffset = 1;

    public NoSlow(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {

        if (predictionComplete.isTeleport() || predictionComplete.isExempt()) return;

        // If the player was using an item for certain, and their predicted velocity had a flipped item
        if (IsUsingItem.isUsingItem(player)) {
            if (bestOffset > offsetToFlag) {
                if (flaggedLastTick) {
                    flagWithSetback();
                }
                flaggedLastTick = true;
            } else {
                reward();
                flaggedLastTick = false;
            }
        }
        bestOffset = 1;
    }

    public void handlePredictionAnalysis(double offset) {
        bestOffset = Math.min(bestOffset, offset);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        offsetToFlag = config.getDoubleElse(getConfigName() + ".threshold", 0.001);
    }
}
