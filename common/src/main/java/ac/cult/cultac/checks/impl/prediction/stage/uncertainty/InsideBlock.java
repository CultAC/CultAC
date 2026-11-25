package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;

public class InsideBlock implements UncertaintyHandler {
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return !context.getWorldData().isMightBeInBlock();
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
        // LocalPlayer#aiStep pushes out of blocks before LivingEntity#jumpFromGround adds the sprint jump's boost.
        return apply(
                start,
                end,
                context.getWorldData().isMightBeInBlock(),
                start.additionOfReason(VelocityTransformer.SPRINT_JUMP_REASON));
    }

    public static PredVector apply(PredVector start, Vec3 end, boolean mightBePushed) {
        return apply(start, end, mightBePushed, Vec3.ZERO);
    }

    private static PredVector apply(PredVector start, Vec3 end, boolean mightBePushed, Vec3 addedAfterPush) {
        if (mightBePushed) {
            Vec3 movementsAllowed = VectorUtils.cutBoxToVector(
                    end,
                    new SimpleCollisionBox(
                            addedAfterPush.x - 0.1,
                            start.y,
                            addedAfterPush.z - 0.1,
                            addedAfterPush.x + 0.1,
                            start.y,
                            addedAfterPush.z + 0.1));
            Vec3 goodVector = VectorUtils.cutBoxToVector(end, new SimpleCollisionBox(start, movementsAllowed).sort());
            return start.with(goodVector, "block pushing");
        }
        return start;
    }
}
