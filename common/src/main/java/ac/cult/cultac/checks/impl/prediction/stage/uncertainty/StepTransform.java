package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

public class StepTransform implements UncertaintyHandler {
    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            MovementTrace trace,
            Vec3 end) {
        double shoveY = player.isBedrockMovement() ? 0.0D : trace.collisionBaseOffset().y;
        return trace.withPosition(step(player, valid, result, trace.position(), shoveY, end));
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            PredVector start,
            Vec3 end) {
        return step(player, valid, result, start, 0.0D, end);
    }

    private static PredVector step(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            PredVector start,
            double shoveY,
            Vec3 end) {
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

        double clampedStep = CultMath.clamp(
                end.y,
                shoveY + Math.min(0, start.y - shoveY),
                shoveY + maxUpStep * result.getSimulationContext().getTargetScalarVert());
        return start.withY(clampedStep, "step").markBoundedStep();
    }
}
