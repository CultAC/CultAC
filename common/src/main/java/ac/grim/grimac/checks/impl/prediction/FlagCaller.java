package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.impl.prediction.profile.MovementProfiles;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import org.bukkit.ChatColor;

import java.util.List;

public class FlagCaller extends GrimProcessor implements PostPredictionListener {
    public FlagCaller(GrimPlayer player) {
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
