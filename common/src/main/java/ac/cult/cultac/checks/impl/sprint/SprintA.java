package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "SprintA",
        stableKey = "cult.sprint.hunger",
        description = "Sprinting with too low hunger",
        setback = 0)
public class SprintA extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("hunger={uint}");

    public SprintA(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {

        if (predictionComplete.isTeleport() || predictionComplete.isExempt()) return;

        // Players can sprint if they're able to fly
        if (player.canFly) return;

        // LocalPlayer#isSprintingPossible reads the food level only of a player that is not a passenger; a passenger
        // is judged by the flag sent after a tick on a vehicle that cannot sprint, which stopped it.
        if (player.compensatedEntities.getSelf().getRiding() != null) {
            PredictionResult result = predictionComplete.getPredictionResult();
            PredictionResult lastResult =
                    player.checkManager.getSimulationProcessor().getLastPrediction();
            if (result == null || !PassengerSprint.stopped(player, result, lastResult)) return;
        }

        if (player.food <= 6.0F) {
            if (player.isSprinting) {
                flagWithSetback(V.write(verbose()).uint(player.food));
            } else {
                reward();
            }
        }
    }
}
