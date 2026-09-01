package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.VectorUtils;
import net.minecraft.world.phys.Vec3;

public class FishingRod implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        SimpleCollisionBox rodPullBox = context.getWorldData().getFishingRodPulls();
        if (rodPullBox == null) return start;

        rodPullBox = rodPullBox.copy().offset(start); // Move the box to be "explosion"-like (relative to start)
        rodPullBox.union(new SimpleCollisionBox(start, start)); // Don't force velocity

        Vec3 clamped = VectorUtils.cutBoxToVector(end, rodPullBox); // Take best interpretation
        return start.with(clamped, "Rod pulls"); // and return it
    }
}
