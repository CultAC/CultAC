package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.CultMath;
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

    @Override
    public void positionInto(
            double[] xyz,
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector carrier,
            Vec3 end) {
        if (!player.isBedrockMovement() && JavaMovementEngine.contextUsesExactEffects(context)) return;
        if (lastResult == null) return;
        Vec3 lastStuckSpeed =
                lastResult.getSimulationContext().getWorldData().getStuckSpeed().getUnknownStuckSpeedMultiplier();
        if (lastStuckSpeed == null) return;
        double x = xyz[0];
        double y = xyz[1];
        double z = xyz[2];
        double stuckX = x * lastStuckSpeed.x;
        double stuckY = y * lastStuckSpeed.y;
        double stuckZ = z * lastStuckSpeed.z;
        xyz[0] = CultMath.clamp(end.x, Math.min(x, stuckX), Math.max(x, stuckX));
        xyz[1] = CultMath.clamp(end.y, Math.min(y, stuckY), Math.max(y, stuckY));
        xyz[2] = CultMath.clamp(end.z, Math.min(z, stuckZ), Math.max(z, stuckZ));
    }
}
