package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(name = "SprintB", stableKey = "cult.sprint.sneaking", description = "Sprinting while sneaking or crawling", setback = 5, experimental = true)
public final class SprintB extends Check implements PostPredictionListener {
    public SprintB(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        // MC-152728 permits sprinting while sneaking on modern versions. The
        // 1.21.4 regression is the sole modern exception retained by Cult 2.0.
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
