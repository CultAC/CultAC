package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.VectorUtils;
import net.minecraft.world.phys.Vec3;

public class StuckSpeed implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        if (lastResult == null) return start;
        Vec3 lastStuckSpeed = lastResult.getSimulationContext().getWorldData().getStuckSpeed().getUnknownStuckSpeedMultiplier();
        if (lastStuckSpeed == null) return start;
        SimpleCollisionBox allowedMovement = new SimpleCollisionBox(start, start.multiply(lastStuckSpeed)).sort();
        Vec3 cut = VectorUtils.cutBoxToVector(end, allowedMovement);
        return start.withXYZ(cut.x, cut.y, cut.z, "maybe stuck speed");
    }
}
