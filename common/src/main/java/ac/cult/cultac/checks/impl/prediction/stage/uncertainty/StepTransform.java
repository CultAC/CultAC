package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import net.minecraft.world.phys.Vec3;

public class StepTransform implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        // The player isn't allowed to step here
        if (!valid.isCanStep()) return start;

        double maxUpStep = start.maxUpStep(player);
        if (maxUpStep <= 0.0F) {
            return start;
        }

        PredVector.StepCandidate step = start.stepCandidate(end, result.getCollideAxisData());
        PredVector candidateStep = step.exact();
        if (candidateStep != null) {
            return candidateStep.distanceToSqr(end) < start.distanceToSqr(end) ? candidateStep : start;
        }
        if (!step.boundedFallback()) {
            return start;
        }

        return boundedCandidate(start, end, maxUpStep, result.getSimulationContext().getTargetScalarVert(), "step")
                .markBoundedStep();
    }

    public static PredVector boundedCandidate(
            PredVector start,
            Vec3 end,
            double maxUpStep,
            double verticalScale,
            String reason
    ) {
        double clampedStep = CultMath.clamp(end.y, Math.min(0, start.y), maxUpStep * verticalScale);
        return start.withY(clampedStep, reason);
    }
}
