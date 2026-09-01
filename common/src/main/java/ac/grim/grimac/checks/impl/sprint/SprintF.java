package ac.grim.grimac.checks.impl.sprint;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;

@CheckData(name = "SprintF", stableKey = "grim.sprint.gliding", description = "Sprinting while gliding", experimental = true)
@DeadCheck(reason = DeadCheck.Reason.VERSION_GATED, detail = "isApplicable() requires clientVersion == V_1_21_4 exactly.")
public class SprintF extends Check implements PostPredictionListener {
    public SprintF(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion() == ClientVersion.V_1_21_4;
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!isApplicable()) return;
        PredictionResult lastPrediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        boolean wasGliding = lastPrediction != null && lastPrediction.getSimulationContext().isGliding();
        if (wasGliding && player.isGliding) {
            if (player.isSprinting) {
                flagWithSetback();
            } else {
                reward();
            }
        }
    }
}
