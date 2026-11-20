package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.CultMath;
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

    @Override
    public void positionInto(
            double[] xyz,
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            PredVector carrier,
            double endX,
            double endY,
            double endZ) {
        boolean mightBePushed = context.getWorldData().isMightBeInBlock();
        // No handler with a position form records the sprint jump's reason, so the lineage since carrier holds none.
        Vec3 addedAfterPush = carrier.additionOfReason(VelocityTransformer.SPRINT_JUMP_REASON);
        if (mightBePushed) {
            double x = xyz[0];
            double y = xyz[1];
            double z = xyz[2];
            double allowedX = CultMath.clamp(endX, addedAfterPush.x - 0.1, addedAfterPush.x + 0.1);
            double allowedY = CultMath.clamp(endY, y, y);
            double allowedZ = CultMath.clamp(endZ, addedAfterPush.z - 0.1, addedAfterPush.z + 0.1);
            xyz[0] = CultMath.clamp(endX, Math.min(x, allowedX), Math.max(x, allowedX));
            xyz[1] = CultMath.clamp(endY, Math.min(y, allowedY), Math.max(y, allowedY));
            xyz[2] = CultMath.clamp(endZ, Math.min(z, allowedZ), Math.max(z, allowedZ));
        }
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
