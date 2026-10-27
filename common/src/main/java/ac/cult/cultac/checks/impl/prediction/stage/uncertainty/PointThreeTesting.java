package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

public class PointThreeTesting implements UncertaintyHandler {
    /** LocalPlayer#sendPosition's withheld-position tolerance never changes Entity#deltaMovement. */
    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        if (valid != null && context.isTestingPointThree())
            valid.noteReportRadius(
                    player.getMovementThreshold()
                            * Math.max(context.getTargetScalarHoriz(), context.getTargetScalarVert()));
        return trace.withPosition(handleUncertainty(player, valid, result, context, lastResult, trace.position(), end));
    }

    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return !context.isTestingPointThree();
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
        // LocalPlayer#sendPosition withholds movement within its report threshold.
        if (context.isTestingPointThree()) {
            double threshold = player.getMovementThreshold();
            threshold *= Math.max(context.getTargetScalarHoriz(), context.getTargetScalarVert());
            start = UncertaintyHelper.handleSpherical(start, end, threshold);
        }
        return start;
    }
}
