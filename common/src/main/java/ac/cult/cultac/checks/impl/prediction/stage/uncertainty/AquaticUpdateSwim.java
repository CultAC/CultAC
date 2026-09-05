package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import net.minecraft.world.phys.Vec3;

public class AquaticUpdateSwim implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        if (context.getWorldData().getInWater() == DesyncStatus.FALSE) return start;
        if (context.getVehicle() != null) return start;

        double lookYAmount = ReachUtils.getLook(player, player.xRot, player.yRot).getY();

        double upwardsSwimReducer = lookYAmount < -0.2 ? 0.085 : 0.06;

        double yChange = ((lookYAmount - start.y) * upwardsSwimReducer);
        double bestY = CultMath.clamp(end.y, start.y, start.y + yChange);

        return start.withY(bestY, "AquaticSwim");
    }
}
