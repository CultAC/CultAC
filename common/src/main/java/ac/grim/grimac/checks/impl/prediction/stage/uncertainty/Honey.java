package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.VectorUtils;
import net.minecraft.world.phys.Vec3;

public class Honey implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
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
