package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "SprintF",
        stableKey = "cult.sprint.gliding",
        description = "Sprinting while gliding",
        experimental = true)
public class SprintF extends Check implements PostPredictionListener {
    public SprintF(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion() == ClientVersion.V_1_21_4;
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!isApplicable()) return;
        PredictionResult lastPrediction =
                player.checkManager.getSimulationProcessor().getLastPrediction();
        boolean wasGliding = lastPrediction != null
                && lastPrediction.getSimulationContext().getVehicle() == null
                && lastPrediction.getSimulationContext().isGliding();
        if (wasGliding && player.isGliding) {
            if (player.isSprinting) {
                flagWithSetback();
            } else {
                reward();
            }
        }
    }
}
