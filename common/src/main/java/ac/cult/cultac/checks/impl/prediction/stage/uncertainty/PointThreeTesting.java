package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.world.phys.Vec3;

public class PointThreeTesting implements UncertaintyHandler{
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        // We are testing 0.03 stuff, expand by 0.03
        if (context.isTestingPointThree()) {
            double threshold = player.getMovementThreshold();
            threshold *= Math.max(context.getTargetScalarHoriz(), context.getTargetScalarVert());
            start = UncertaintyHelper.handleSpherical(start, end, threshold);
        }
        return start;
    }
}
