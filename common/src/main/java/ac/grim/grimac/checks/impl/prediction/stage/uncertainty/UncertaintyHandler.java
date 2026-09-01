package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.phys.Vec3;

public interface UncertaintyHandler {
    default MovementTrace handleMovementTrace(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, MovementTrace trace, Vec3 end) {
        return trace.withPosition(handleUncertainty(player, valid, result, context, lastResult, trace.position(), end));
    }

    PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end);
}
