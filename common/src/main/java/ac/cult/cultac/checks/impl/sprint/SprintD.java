package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "SprintD",
        stableKey = "cult.sprint.blindness",
        description = "Sprinted while having blindness",
        setback = 5,
        experimental = true)
public class SprintD extends Check implements PostPredictionListener {
    public SprintD(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (predictionComplete.isTeleport() || predictionComplete.isExempt()) return;

        // LocalPlayer#aiStep stops a sprint once Player#isMobilityRestricted fails LocalPlayer#isSprintingPossible; a
        // passenger is judged by the flag sent after a tick on a vehicle that cannot sprint, which stopped it.
        if (player.compensatedEntities.getSelf().getRiding() != null) {
            PredictionResult result = predictionComplete.getPredictionResult();
            PredictionResult lastResult =
                    player.checkManager.getSimulationProcessor().getLastPrediction();
            if (result == null || !PassengerSprint.stopped(player, result, lastResult)) return;
        }

        if (player.compensatedEntities.hasPotionEffect(MovementEffect.BLINDNESS)) {
            if (player.isSprinting) {
                flagWithSetback();
            } else reward();
        }
    }
}
