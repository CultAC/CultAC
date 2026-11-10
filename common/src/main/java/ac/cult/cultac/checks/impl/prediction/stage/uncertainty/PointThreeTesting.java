package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

public class PointThreeTesting implements UncertaintyHandler {
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
        // We are testing 0.03 stuff, expand by 0.03
        if (context.isTestingPointThree()) {
            double threshold = player.getMovementThreshold();
            threshold *= Math.max(context.getTargetScalarHoriz(), context.getTargetScalarVert());
            start = UncertaintyHelper.handleSpherical(start, end, threshold);
        }
        return start;
    }

    /** Coordinate {@code axis} (0 x, 1 y, 2 z) of {@link #handleUncertainty}'s result. */
    double coordinate(CultPlayer player, SimulationContext context, PredVector start, Vec3 end, int axis) {
        return coordinate(player, context, start.x, start.y, start.z, end, axis);
    }

    /** {@link #coordinate} for a start at {@code x}, {@code y}, {@code z}. */
    double coordinate(CultPlayer player, SimulationContext context, double x, double y, double z, Vec3 end, int axis) {
        if (context.isTestingPointThree()) {
            double threshold = player.getMovementThreshold();
            threshold *= Math.max(context.getTargetScalarHoriz(), context.getTargetScalarVert());
            return UncertaintyHelper.sphericalCoordinate(x, y, z, end, threshold, axis);
        }
        return axis == 0 ? x : axis == 1 ? y : z;
    }
}
