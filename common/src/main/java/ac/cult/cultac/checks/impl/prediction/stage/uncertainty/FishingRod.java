package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.VectorUtils;
import net.minecraft.world.phys.Vec3;

public class FishingRod implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        SimpleCollisionBox rodPullBox = context.getWorldData().getFishingRodPulls();
        if (rodPullBox == null) return start;

        rodPullBox = rodPullBox.copy().offset(start); // Move the box to be "explosion"-like (relative to start)
        rodPullBox.union(new SimpleCollisionBox(start, start)); // Don't force velocity

        Vec3 clamped = VectorUtils.cutBoxToVector(end, rodPullBox); // Take best interpretation
        return start.with(clamped, "Rod pulls"); // and return it
    }
}
