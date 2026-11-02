package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;

public class StuckSpeed implements UncertaintyHandler {
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return !player.isBedrockMovement() && JavaMovementEngine.contextUsesExactEffects(context)
                || lastResult == null
                || lastResult
                                .getSimulationContext()
                                .getWorldData()
                                .getStuckSpeed()
                                .getUnknownStuckSpeedMultiplier()
                        == null;
    }

    /**
     * Entity#move scales only the move by Entity#stuckSpeedMultiplier and then clears deltaMovement, which
     * VelocityCandidates carries as zero, so the scaled move is not velocity.
     */
    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        return trace.withPosition(handleUncertainty(player, valid, result, context, lastResult, trace.position(), end));
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end) {
        if (!player.isBedrockMovement() && JavaMovementEngine.contextUsesExactEffects(context)) return start;
        if (lastResult == null) return start;
        Vec3 lastStuckSpeed =
                lastResult.getSimulationContext().getWorldData().getStuckSpeed().getUnknownStuckSpeedMultiplier();
        if (lastStuckSpeed == null) return start;
        SimpleCollisionBox allowedMovement = new SimpleCollisionBox(start, start.multiply(lastStuckSpeed)).sort();
        Vec3 cut = VectorUtils.cutBoxToVector(end, allowedMovement);
        return start.withXYZ(cut.x, cut.y, cut.z, "maybe stuck speed");
    }
}
