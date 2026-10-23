package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.List;

/**
 * The vertical fluid input before travel, in client order: LocalPlayer#aiStep's goDownInWater while the shift key
 * is down, LivingEntity#aiStep's 0.003 threshold and jumpInLiquid, then Player#travel's swimming look pull. Each is
 * applied whole or not at all; the jump key and the client's own swimming flag are the only open choices. While the
 * swimming flag is open the candidate carries the one it travelled with, which the tick commits.
 */
public class SwimInput implements UncertaintyHandler {
    private static final List<Boolean> NO = List.of(false);
    private static final List<Boolean> YES = List.of(true);
    private static final List<Boolean> EITHER = List.of(false, true);
    // Swimming first: a movement that cannot tell the two apart keeps WorldData's optimistic swim.
    private static final List<Boolean> SWIMMING_FIRST = List.of(true, false);

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end) {
        var world = result.getSimulationContext().getWorldData();
        if (!world.maybeInLiquid() || context.getVehicle() != null || player.isFlying) return start;

        // Entity#isAffectedByFluids is !abilities.flying; goDownInWater reads isInWater and the shift key.
        List<Boolean> goingDown = context.isSneaking() ? world.getInWater().getStates() : NO;
        // Entity#updateSwimming in this tick's Entity#baseTick; only an unknown water state leaves it open.
        boolean open = world.isSwimmingOpen();
        List<Boolean> swimming = open ? SWIMMING_FIRST : world.isSwimming() ? YES : NO;
        double lookY =
                ReachUtils.getLook(player, context.getXRot(), context.getYRot()).getY();
        Vec3 from = context.getStart();
        boolean fluidAbove = !ClientFluidQueries.fluidAt(
                        player.compensatedWorld, BlockPos.containing(from.x, from.y + 1.0 - 0.1, from.z))
                .isEmpty();

        // VelocityTransformer already applied LivingEntity#aiStep's threshold, which follows goDownInWater.
        double startY = start.y;
        if (startY == 0.0) {
            double unthresholded = start.vectorBeforeFirstReason("Inertia threshold").y;
            if (Math.abs(unthresholded) < 0.003) startY = unthresholded;
        }

        SimpleCollisionBox box = context.getFromActualPose();
        Vec3 stuck = start.collisionStuckSpeedMultiplier(context);
        double stuckY = stuck == null || stuck.y == 0.0 ? 1.0 : stuck.y;
        double bestY = start.y;
        boolean bestSwimming = world.isSwimming();
        double bestDistance = Double.MAX_VALUE;
        for (boolean down : goingDown) {
            double y = down ? startY - 0.04F : startY;
            // LivingEntity#aiStep zeroes a small deltaY after goDownInWater and before jumpInLiquid.
            if (Math.abs(y) < 0.003) y = 0.0;
            for (boolean jumping : EITHER) {
                double jumped = jumping ? y + 0.04F : y;
                for (boolean swim : swimming) {
                    double travelY = jumped;
                    if (swim && (lookY <= 0.0 || jumping || fluidAbove)) {
                        travelY += (lookY - travelY) * (lookY < -0.2 ? 0.085 : 0.06);
                    }
                    double distance = Math.abs(clipY(player, box, stuckY, travelY) - end.y);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestY = travelY;
                        bestSwimming = swim;
                    }
                }
            }
        }
        PredVector moved = start.withXYZOrSelf(start.x, bestY, start.z, "swim input");
        return open ? moved.withSwimming(bestSwimming) : moved;
    }

    /**
     * The y Entity#move ends at for a travel delta of {@code y}. The handlers select with collisions ignored, but a
     * floor or ceiling makes candidates on its far side equal, so they are ranked by where they end. Entity#collide
     * resolves Y first (Direction#axisStepOrder), from the start box.
     */
    private static double clipY(CultPlayer player, SimpleCollisionBox box, double stuckY, double y) {
        if (y == 0.0) return 0.0;
        return Collisions.collide(player, box, 0.0, y * stuckY, 0.0).y / stuckY;
    }
}
