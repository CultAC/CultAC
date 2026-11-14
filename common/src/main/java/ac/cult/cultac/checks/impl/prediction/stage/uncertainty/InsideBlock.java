package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.world.BlockPush;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import java.util.List;

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
        Vec3 addedAfterPush = start.additionOfReason(VelocityTransformer.SPRINT_JUMP_REASON);
        List<BlockPush> pushes = exactPushes(context, start);
        if (pushes == null) {
            return apply(start, end, context.getWorldData().isMightBeInBlock(), addedAfterPush);
        }
        // The client sets one of the pushes; the one nearest the movement explains it.
        Vec3 nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPush push : pushes) {
            Vec3 pushed = new Vec3(
                    push.pushesX() ? push.x() + addedAfterPush.x : start.x,
                    start.y,
                    push.pushesZ() ? push.z() + addedAfterPush.z : start.z);
            double distance = (pushed.x - end.x) * (pushed.x - end.x) + (pushed.z - end.z) * (pushed.z - end.z);
            if (distance >= nearestDistance) continue;
            nearestDistance = distance;
            nearest = push.pushes() ? pushed : null;
        }
        return nearest == null ? start : start.with(nearest, "block pushing");
    }

    /**
     * The pushes LocalPlayer#moveTowardsClosestSpace may set this tick, one of which it sets, when at least one pushes
     * and the engine knows them exactly, else null. A dry glide has none: LivingEntity#travelFallFlying turns the
     * pushed velocity, and {@link ElytraTransform} runs before this handler.
     */
    static List<BlockPush> exactPushes(SimulationContext context, PredVector start) {
        List<BlockPush> pushes = context.getWorldData().getBlockPushes();
        if (pushes.stream().noneMatch(BlockPush::pushes) || context.isTestingPointThree() || start.isTickSkip())
            return null;
        if (context.usesFallFlyingMovement() && !context.getWorldData().mustBeInLiquid()) return null;
        return pushes;
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
        List<BlockPush> pushes = exactPushes(context, carrier);
        if (pushes != null) {
            double x = xyz[0];
            double z = xyz[2];
            boolean pushed = false;
            double pushedX = x;
            double pushedZ = z;
            double nearestDistance = Double.MAX_VALUE;
            for (BlockPush push : pushes) {
                double candidateX = push.pushesX() ? push.x() + addedAfterPush.x : x;
                double candidateZ = push.pushesZ() ? push.z() + addedAfterPush.z : z;
                double distance = (candidateX - endX) * (candidateX - endX) + (candidateZ - endZ) * (candidateZ - endZ);
                if (distance >= nearestDistance) continue;
                nearestDistance = distance;
                pushed = push.pushes();
                pushedX = candidateX;
                pushedZ = candidateZ;
            }
            if (pushed) {
                xyz[0] = pushedX;
                xyz[2] = pushedZ;
            }
            return;
        }
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
