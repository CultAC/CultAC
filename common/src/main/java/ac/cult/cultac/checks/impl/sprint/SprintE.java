package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "SprintE",
        stableKey = "cult.sprint.wall",
        description = "Sprinting while colliding with a wall",
        setback = 5,
        experimental = true)
public final class SprintE extends Check implements PostPredictionListener {
    private boolean wasHardHorizontalCollision;
    private boolean previousPredictionChecked;
    private ClientHorizontalCollision.Input previousInput;

    public SprintE(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (complete.isTeleport()) {
            wasHardHorizontalCollision = false;
            previousPredictionChecked = false;
            previousInput = null;
            return;
        }

        PredictionResult result = complete.getPredictionResult();
        boolean checked = !complete.isExempt() && result != null;
        boolean inWater = result != null
                && result.getSimulationContext().getWorldData().getInWater().determineOptimistically();
        DesyncStatus shoved = checked && previousInput != null
                ? ClientHorizontalCollision.hardBeforeTravel(result, previousInput)
                : null;
        boolean wasHard = shoved == null ? wasHardHorizontalCollision : shoved.determinePessimistically();

        // LocalPlayer#aiStep stops a sprint it started in the same tick once the last move collided hard, so no
        // START precedes a sprinting move after one.
        if (checked
                && previousPredictionChecked
                && wasHard
                && !player.inVehicle()
                && !inWater) {
            if (player.isSprinting) {
                flagWithSetback();
            } else {
                reward();
            }
        }

        wasHardHorizontalCollision =
                checked && ClientHorizontalCollision.hard(result).determinePessimistically();
        previousPredictionChecked = checked;
        previousInput = checked ? ClientHorizontalCollision.input(result) : null;
    }
}
