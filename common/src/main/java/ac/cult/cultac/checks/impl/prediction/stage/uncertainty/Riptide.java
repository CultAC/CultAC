package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import net.minecraft.world.phys.Vec3;

public class Riptide implements UncertaintyHandler {

    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        return handleMovementTrace(player, valid, result, context, lastContext, MovementTrace.start(start), end).position();
    }

    @Override
    public MovementTrace handleMovementTrace(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, MovementTrace trace, Vec3 end) {
        PredVector start = trace.position();

        if (lastContext != null && lastContext.getSimulationContext().getRiptideLevel() != 0) {
            // The teleport upwards by 1.2 screwed up the next predicted velocity
            if (end.y < start.y) {
                start = UncertaintyHelper.handleVertical(start, end, 1.2);
            }
        }

        if (context.getRiptideLevel() == 0) return trace.withPosition(start);
        float riptideAdditionalVelocity = 3.0F * ((1.0F + context.getRiptideLevel()) / 4.0F);

        result.getSimulationContext().getWorldData().setLastOnGround(DesyncStatus.UNKNOWN);

        // TODO: Force people to be touching water to use riptide
        // TODO: Rate limit riptide
        // TODO: Fix false for the next tick when riptiding on ground
        if (result.getSimulationContext().getLastOnGround().determineOptimistically()) {
            double bestY = CultMath.clamp(end.y, start.y, start.y + 1.2);
            PredVector lifted = start.withY(bestY, "riptide ground lift");
            // MCP-Reborn TridentItem#releaseUsing first writes the riptide
            // impulse with Entity#push, then grounded players are moved upward
            // by Entity#move(MoverType.SELF, 0, 1.1999999, 0). That lift is a
            // packet-visible position move, not additional carried velocity.
            trace = trace.withPosition(lifted).addPositionOnlyDelta(lifted.subtract(start));
            start = lifted;
        }

        start = UncertaintyHelper.handleSpherical(start, end, riptideAdditionalVelocity);
        return trace.withPosition(start);
    }
}
