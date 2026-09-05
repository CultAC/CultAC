package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.VectorUtils;
import net.minecraft.world.phys.Vec3;

public class StuckSpeed implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        if (!player.isBedrockMovement() && ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine.contextUsesExactEffects(context)) return start;
        if (lastResult == null) return start;
        Vec3 lastStuckSpeed = lastResult.getSimulationContext().getWorldData().getStuckSpeed().getUnknownStuckSpeedMultiplier();
        if (lastStuckSpeed == null) return start;
        SimpleCollisionBox allowedMovement = new SimpleCollisionBox(start, start.multiply(lastStuckSpeed)).sort();
        Vec3 cut = VectorUtils.cutBoxToVector(end, allowedMovement);
        return start.withXYZ(cut.x, cut.y, cut.z, "maybe stuck speed");
    }
}
