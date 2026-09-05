package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.world.phys.Vec3;

public class Fireworks implements UncertaintyHandler {

    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        if (player.compensatedFireworks.getMaxFireworksAppliedPossible() == 0) return start;
        // TODO: I was incorrect in tick order being inconsistent. We can simulate this fine.
        return UncertaintyHelper.handleSpherical(start, end, 1.7);
    }
}
