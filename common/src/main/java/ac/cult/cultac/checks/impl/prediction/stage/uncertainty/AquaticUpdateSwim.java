package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaTravelInputs;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.ReachUtils;

public class AquaticUpdateSwim implements UncertaintyHandler {
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        if (JavaTravelInputs.usesReportedKeys(player, context))
            return context.getVehicle() != null || !context.getWorldData().isSwimming() || player.isFlying;
        return !context.getWorldData().isInWater() || context.getVehicle() != null;
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
        if (context.getVehicle() != null) return start;
        boolean exactInput = JavaTravelInputs.usesReportedKeys(player, context);
        if (!exactInput && !context.getWorldData().isInWater()) return start;
        // Player#travel adjusts deltaMovement towards the look angle only while Entity#isSwimming is set.
        if (!player.isBedrockMovement() && !context.getWorldData().isSwimming()) return start;

        double lookYAmount = ReachUtils.getLook(
                        player,
                        exactInput ? context.getXRot() : player.xRot,
                        exactInput ? context.getYRot() : player.yRot)
                .getY();

        double upwardsSwimReducer = lookYAmount < -0.2 ? 0.085 : 0.06;

        double yChange = ((lookYAmount - start.y) * upwardsSwimReducer);
        if (exactInput) {
            if (player.isFlying) return start;
            var position = context.getStart();
            if (lookYAmount > 0.0D
                    && !player.packetStateData.knownInput.jump()
                    && ClientFluidQueries.fluidAt(
                                    player.compensatedWorld,
                                    BlockPos.containing(position.x, position.y + 1.0D - 0.1D, position.z))
                            .isEmpty()) return start;
            return start.add(0, yChange, 0, "Player#travel swimming pitch");
        }
        double bestY = CultMath.clamp(end.y, start.y, start.y + yChange);

        return start.withXYZOrSelf(start.x, bestY, start.z, "AquaticSwim");
    }
}
