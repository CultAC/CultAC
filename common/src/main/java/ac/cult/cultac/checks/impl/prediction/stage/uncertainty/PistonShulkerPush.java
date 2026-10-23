package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.data.ShulkerBoxShoveResult;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import java.util.Set;

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
        ExternalMovementUncertainty.Snapshot snapshot = ExternalMovementUncertainty.capture(context, lastResult);
        SimpleCollisionBox expanded = snapshot.combinedTargetEnvelopeCopy();

        if (expanded.isEmpty()) return trace;

        PistonPushes pushes = context.getWorldData().getPistonPushes();
        // CollisionModifier moves the travel's end by the shulker box block shoves after it.
        if (pushes.getShulkerBoxShovesAfterTravel() != null) return trace;
        Boolean groundAfterShove = pushes.onGroundAfterShoveBeforeTravel();
        result.getSimulationContext()
                .getWorldData()
                .setLastOnGround(
                        groundAfterShove == null ? DesyncStatus.UNKNOWN : DesyncStatus.fromBoolean(groundAfterShove));

        // Clients before 26.3 report a block-entity pass's shove and launch together, before the next travel.
        if (start.hasReason(MovementModifiers.SLIME_BLOCK_LAUNCH)
                && !player.isBedrockMovement()
                && !player.compensatedWorld.pistons.reportsAfterBlockEntities()) {
            shoveAlongLaunch(context, snapshot.currentPistonTargetEnvelope(), expanded);
        }
        ShulkerBoxShoveResult shulkerBoxShove = pushes.getShulkerBoxShoveResult();
        if (shulkerBoxShove != null)
            expanded = new SimpleCollisionBox(shulkerBoxShove.movement(), shulkerBoxShove.movement());

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
            Vec3 positionOnlyDelta = targetSpaceOffset.multiply(stuckSpeed.x, stuckSpeed.y, stuckSpeed.z);
            trace = trace.addPositionOnlyDelta(positionOnlyDelta);
        }
        return trace.addCollisionBaseOffset(targetSpaceOffset);
    }

    /**
     * PistonMovingBlockEntity#moveCollidedEntities launches an entity off a slime block in the pass that shoves it by
     * getMovement plus 0.01, so a launched start moves exactly the current shove along the launch axis.
     */
    private static void shoveAlongLaunch(
            SimulationContext context, SimpleCollisionBox currentPush, SimpleCollisionBox envelope) {
        Set<Direction> launches = context.getWorldData().getPistonPushes().getSlimeBlockLaunches();
        if (launches.size() != 1 || currentPush.isEmpty()) return;
        Direction launch = launches.iterator().next();
        if (launch.getModX() != 0) {
            envelope.minX = envelope.maxX = launch.getModX() > 0 ? currentPush.maxX : currentPush.minX;
        } else if (launch.getModY() != 0) {
            envelope.minY = envelope.maxY = launch.getModY() > 0 ? currentPush.maxY : currentPush.minY;
        } else {
            envelope.minZ = envelope.maxZ = launch.getModZ() > 0 ? currentPush.maxZ : currentPush.minZ;
        }
    }
}
