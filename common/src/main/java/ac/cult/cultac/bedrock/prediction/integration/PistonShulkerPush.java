package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;

/**
 * The Bedrock engine's shared piston and shulker push handler: the move may take any shove in the
 * {@link ExternalMovementUncertainty} envelope, as a position-only displacement. Java movement replays the client's
 * block-entity pass instead (JavaBlockEntityPass).
 */
public class PistonShulkerPush implements UncertaintyHandler {

    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return !ExternalMovementUncertainty.mayShove(context, lastResult);
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
        MovementTrace transformed =
                handleMovementTrace(player, valid, result, context, lastResult, MovementTrace.start(start), end);
        Vec3 collisionBaseOffset = transformed.collisionBaseOffset();
        return collisionBaseOffset.lengthSqr() <= 1.0E-14
                ? transformed.position()
                : transformed.position().add(collisionBaseOffset, "Piston/Shulker Push");
    }

    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        if (!ExternalMovementUncertainty.mayShove(context, lastResult)) return trace;
        PredVector start = trace.position();
        SimpleCollisionBox expanded =
                ExternalMovementUncertainty.capture(context, lastResult).combinedTargetEnvelopeCopy();
        if (expanded.isEmpty()) return trace;

        result.getSimulationContext().getWorldData().setLastOnGround(DesyncStatus.UNKNOWN);

        expanded.offset(start);
        Vec3 cut = VectorUtils.cutBoxToVector(end, expanded);
        PredVector moved = start.withXYZ(cut.x, cut.y, cut.z, "Piston/Shulker Push");
        Vec3 targetSpaceOffset = moved.subtract(start);

        if (valid == null || !valid.isTestingMaxStartingVelExtents(end)) {
            // The shove changes position without becoming deltaMovement. The accepted target is in pre-stuck
            // coordinates, so the position-only shove is converted back to client movement before the next tick's
            // velocity is derived.
            Vec3 stuckSpeed = context.getLastStuckSpeed();
            Vec3 positionOnlyDelta = targetSpaceOffset.multiply(stuckSpeed.x, stuckSpeed.y, stuckSpeed.z);
            trace = trace.addPositionOnlyDelta(positionOnlyDelta);
        }
        return trace.addCollisionBaseOffset(targetSpaceOffset);
    }
}
