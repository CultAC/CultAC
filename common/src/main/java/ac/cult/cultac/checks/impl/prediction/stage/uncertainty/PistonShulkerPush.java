package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.VectorUtils;
import net.minecraft.world.phys.Vec3;

public class PistonShulkerPush implements UncertaintyHandler {

    @Override
    public PredVector handleUncertainty(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, PredVector start, Vec3 end) {
        MovementTrace transformed = handleMovementTrace(player, valid, result, context, lastResult, MovementTrace.start(start), end);
        Vec3 collisionBaseOffset = transformed.collisionBaseOffset();
        return collisionBaseOffset.lengthSqr() <= 1.0E-14
                ? transformed.position()
                : transformed.position().add(collisionBaseOffset, "Piston/Shulker Push");
    }

    @Override
    public MovementTrace handleMovementTrace(CultPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastResult, MovementTrace trace, Vec3 end) {
        PredVector start = trace.position();
        ExternalMovementUncertainty.Snapshot snapshot = ExternalMovementUncertainty.capture(context, lastResult);
        SimpleCollisionBox expanded = snapshot.combinedTargetEnvelopeCopy();

        if (expanded == null || expanded.isEmpty()) return trace;

        result.getSimulationContext().getWorldData().setLastOnGround(DesyncStatus.UNKNOWN);

        expanded.offset(start);
        Vec3 cut = VectorUtils.cutBoxToVector(end, expanded);
        PredVector moved = start.withXYZ(cut.x, cut.y, cut.z, "Piston/Shulker Push");
        Vec3 targetSpaceOffset = moved.subtract(start);

        if (valid == null || !valid.isTestingMaxStartingVelExtents(end)) {
            // MCP-Reborn PistonMovingBlockEntity#moveEntityByPiston and
            // ShulkerBoxBlockEntity#moveCollidedEntities call Entity#move with
            // MoverType.PISTON or MoverType.SHULKER. That changes position but
            // does not assign the shove displacement to Entity#deltaMovement;
            // only collision axis zeroing and block speed factor can affect the
            // existing velocity.
            // Entity#move applies Entity#stuckSpeedMultiplier to every MoverType
            // except PISTON, then clears the multiplier. Cult compares the accepted
            // target in pre-stuck coordinates, so convert the position-only shove
            // back to raw client movement before deriving next tick velocity.
            Vec3 stuckSpeed = context.getLastStuckSpeed();
            Vec3 multiplier = stuckSpeed == null ? new Vec3(1.0D, 1.0D, 1.0D) : stuckSpeed;
            Vec3 positionOnlyDelta = targetSpaceOffset.multiply(multiplier.x, multiplier.y, multiplier.z);
            trace = trace.addPositionOnlyDelta(positionOnlyDelta);
        }
        return trace.addCollisionBaseOffset(targetSpaceOffset);
    }
}
