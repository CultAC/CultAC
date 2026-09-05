package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import org.bukkit.ChatColor;

import java.util.List;

public class FlagCaller extends CultProcessor implements PostPredictionListener {
    public FlagCaller(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.shouldEnforceMovementSetbacks()) return;
        if (!MovementProfiles.forPlayer(player).usesSharedOffsetSetbacks(player)) return;

        if (!predictionComplete.isExempt() && predictionComplete.getPredictionResult() != null) {
            List<PredictionResult.Flag> flags = predictionComplete.getPredictionResult().getFlags();

            for (PredictionResult.Flag flag : flags) {
                flag.getCheck().flag(flag.getVerbose().getString() + " " + ChatColor.DARK_GRAY + predictionComplete.getPredictionResult().getIdentifier());
            }
        }
    }
}
