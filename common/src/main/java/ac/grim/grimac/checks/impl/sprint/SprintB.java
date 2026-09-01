package ac.grim.grimac.checks.impl.sprint;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;

@CheckData(name = "SprintB", stableKey = "grim.sprint.sneaking", description = "Sprinting while sneaking or crawling", setback = 5, experimental = true)
public final class SprintB extends Check implements PostPredictionListener {
    public SprintB(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        // MC-152728 permits sprinting while sneaking on modern versions. The
        // 1.21.4 regression is the sole modern exception retained by Grim 2.0.
        return player.getClientVersion() == ClientVersion.V_1_21_4;
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (!isApplicable() || complete.isTeleport() || complete.isExempt()) return;

        PredictionResult result = complete.getPredictionResult();
        if (result == null
                || !result.getSimulationContext().isSneaking()
                || !result.getSimulationContext().getWorldData().getInWater().determinePessimistically()) {
            return;
        }

        if (player.isSprinting) {
            flagWithSetback();
        } else {
            reward();
        }
    }
}
