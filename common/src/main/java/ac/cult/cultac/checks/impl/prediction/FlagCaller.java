package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.StringReturner;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import java.util.List;

public class FlagCaller extends CultProcessor implements PostPredictionListener {
    public FlagCaller(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.shouldEnforceMovementSetbacks()) return;
        boolean sharedSetbacks = MovementProfiles.forPlayer(player).usesSharedOffsetSetbacks(player);

        PredictionResult result = predictionComplete.getPredictionResult();
        if (!predictionComplete.isExempt() && result != null) {
            List<PredictionResult.Flag> flags = result.getFlags();

            for (int i = 0; i < flags.size(); i++) {
                PredictionResult.Flag flag = flags.get(i);
                if (!sharedSetbacks && !(flag.getCheck() instanceof BedrockMovement)) continue;
                if (flag.getCheck() instanceof BedrockMovement movement) {
                    movement.flagMovement(flag.getSeverity(), result::shownIdentifier);
                    continue;
                }
                StringReturner verbose = flag.getVerbose();
                // Only the alert shows the identifier, and builds its Cult Log, if something renders it.
                flag.getCheck()
                        .flag(
                                verbose::getString,
                                () -> verbose.getString() + " " + "\u00a78" + result.shownIdentifier());
            }
        }
    }
}
