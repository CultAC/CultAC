package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.VectorUtils;
import net.minecraft.world.phys.Vec3;

public class InsideBlock implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        // Bedrock's end-of-tick block push can move the player after its pose shrinks.
        boolean previousOverlap = player != null && player.isBedrockMovement()
            && lastContext != null && lastContext.getSimulationContext() != null
            && lastContext.getSimulationContext().getWorldData().isMightBeInBlock();
        boolean mightBePushed = player != null && player.isBedrockMovement()
            ? previousOverlap : context.getWorldData().isMightBeInBlock();
        if (mightBePushed) {
            Vec3 movementsAllowed = VectorUtils.cutBoxToVector(end, new SimpleCollisionBox(-0.1, start.y, -0.1, 0.1, start.y, 0.1));
            Vec3 goodVector = VectorUtils.cutBoxToVector(end, new SimpleCollisionBox(start, movementsAllowed).sort());
            return start.with(goodVector, "block pushing");
        }
        return start;
    }
}
