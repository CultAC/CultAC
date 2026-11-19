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

    public SprintE(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        // A pass replayed since the last travel (a block entity after the report before 26.3, a shulker ticking before
        // the player) ran Entity#move after it, which set both flags again from its own movement: LocalPlayer#aiStep
        // reads its collision in place of the travel's.
        DesyncStatus afterPass = player.checkManager.getSimulationProcessor().takeHardCollisionAfterPass();
        if (afterPass != null) wasHardHorizontalCollision = afterPass.determinePessimistically();
        if (complete.isTeleport()) {
            wasHardHorizontalCollision = false;
            previousPredictionChecked = false;
            return;
        }

        PredictionResult result = complete.getPredictionResult();
        // A move Simulation accepts commits its explaining candidate, collision included; one it flags leaves the
        // collision unknown. A residual below its threshold therefore cannot hide a hard collision.
        boolean checked = !complete.isExempt()
                && result != null
                && player.checkManager.getOffsetHandler().accepts(result);
        boolean inWater = result != null
                && result.getSimulationContext().getWorldData().isInWater();

        // LocalPlayer#aiStep stops a sprint it started in the same tick once the last move collided hard, so no
        // START precedes a sprinting move after one.
        if (checked
                && previousPredictionChecked
                && wasHardHorizontalCollision
                && !player.inVehicle()
                && !inWater) {
            if (player.isSprinting) {
                flagWithSetback();
            } else {
                reward();
            }
        }

        // Only a hard collision every candidate agrees on stops the sprint for certain.
        wasHardHorizontalCollision =
                checked && ClientHorizontalCollision.hardForEveryReality(result).determinePessimistically();
        previousPredictionChecked = checked;
    }
}
