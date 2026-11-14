package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;

public class AquaticUpdateSwim implements UncertaintyHandler {
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return context.getWorldData().getInWater() == DesyncStatus.FALSE || context.getVehicle() != null;
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            PredVector start,
            Vec3 end) {
        if (context.getWorldData().getInWater() == DesyncStatus.FALSE) return start;
        if (context.getVehicle() != null) return start;

        return start.withXYZOrSelf(start.x, swimY(player, start.y, end.y), start.z, "AquaticSwim");
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
            double endX,
            double endY,
            double endZ) {
        if (context.getWorldData().getInWater() == DesyncStatus.FALSE) return;
        if (context.getVehicle() != null) return;

        xyz[1] = swimY(player, xyz[1], endY);
    }

    private static double swimY(CultPlayer player, double y, double endY) {
        double lookYAmount =
                ReachUtils.getLook(player, player.xRot, player.yRot).getY();

        double upwardsSwimReducer = lookYAmount < -0.2 ? 0.085 : 0.06;

        double yChange = ((lookYAmount - y) * upwardsSwimReducer);
        return CultMath.clamp(endY, y, y + yChange);
    }
}
