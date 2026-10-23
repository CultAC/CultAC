package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

public class SwimSpaceShift implements UncertaintyHandler {
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return !context.getWorldData().maybeInLiquid() || context.getVehicle() != null;
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
        if (!result.getSimulationContext().getWorldData().maybeInLiquid()) return start;
        if (context.getVehicle() != null) return start;

        if (end.y - start.y > 0) {
            double baseY = applyLivingEntityThresholdBeforeWaterJump(start.y);
            return start.withY(CultMath.clamp(end.y, baseY, baseY + 0.04F), "swim space");
        } else if (context.isSneaking()) {
            return start.withY(CultMath.clamp(end.y, start.y, start.y - 0.04F), "swim sneak");
        }

        return start;
    }

    private double applyLivingEntityThresholdBeforeWaterJump(double y) {
        // MCP-Reborn Entity#onAboveBubbleColumn/onInsideBubbleColumn mutates deltaMovement
        // after travel. On the next tick, LivingEntity#aiStep zeroes |deltaY| < 0.003
        // before LivingEntity#jumpInLiquid adds the 0.04 water jump impulse.
        if (Math.abs(y) < 0.003D) {
            return 0.0D;
        }
        return y;
    }
}
