package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.VectorUtils;
import net.minecraft.world.phys.Vec3;

public class Honey implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        if (!player.isBedrockMovement() && ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine.contextUsesExactEffects(context)) return start;
        // Based on last tick
        if (lastResult == null || !lastResult.getSimulationContext().getWorldData().getHoneySlide().determineOptimistically()) return start;
        // Only possible when vector.y >= -0.08D
        // Gliding does its own stuff with gravity so just allow gliding to use this.
        if (!context.usesFallFlyingMovement() && lastResult.getValidMovements().getCollisionIgnoredMaxStartingVelExtents().minY > -0.08D) return start;

        SimpleCollisionBox validMove = new SimpleCollisionBox(start, new Vec3(0, -0.05D, 0)).sort();
        Vec3 cut = VectorUtils.cutBoxToVector(end, validMove);
        return start.withXYZ(cut.x, cut.y, cut.z, "honey");
    }
}
