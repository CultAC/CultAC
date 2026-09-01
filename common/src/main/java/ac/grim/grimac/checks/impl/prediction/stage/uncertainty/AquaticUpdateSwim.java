package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.DesyncStatus;
import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import net.minecraft.world.phys.Vec3;

public class AquaticUpdateSwim implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        if (context.getWorldData().getInWater() == DesyncStatus.FALSE) return start;
        if (context.getVehicle() != null) return start;

        double lookYAmount = ReachUtils.getLook(player, player.xRot, player.yRot).getY();

        double upwardsSwimReducer = lookYAmount < -0.2 ? 0.085 : 0.06;

        double yChange = ((lookYAmount - start.y) * upwardsSwimReducer);
        double bestY = GrimMath.clamp(end.y, start.y, start.y + yChange);

        return start.withY(bestY, "AquaticSwim");
    }
}
