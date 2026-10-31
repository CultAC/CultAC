package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaForwardMove;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.data.StuckEdgeData;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;

public class Sneaking implements UncertaintyHandler {
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        StuckEdgeData lastOnEdge = lastResult == null
                ? null
                : lastResult.getSimulationContext().getWorldData().getSneak();
        return (lastOnEdge == null || !lastOnEdge.isOnEdge())
                && !context.getWorldData().getSneak().isOnEdge();
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
        return backOffFromEdge(
                result, lastResult, hiddenVelocity(player, result, context, lastResult, start, end), end);
    }

    /**
     * The hidden velocity is deltaMovement the client built up, while Player#maybeBackOffFromEdge shortens only the
     * move Entity#move makes. Where the backoff shortened an axis the move does not show the velocity behind it, so
     * that axis keeps the velocity from before this handler, and the next tick estimates it again from the edge.
     */
    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        // A forward candidate's Entity#move runs Player#maybeBackOffFromEdge on its exact delta, and its carry keeps
        // the deltaMovement the backoff hid, so neither the hidden velocity nor this projection applies.
        if (JavaForwardMove.appliesTo(result)) return trace;
        var pass = trace.passAfterTravel();
        if (pass != null) {
            Vec3 scale = context.getLastStuckSpeed();
            Vec3 shove = pass.movement().multiply(1 / scale.x, 1 / scale.y, 1 / scale.z);
            PredVector travel = trace.position().with(trace.position().subtract(shove), "travel before late shove");
            Vec3 target = end.subtract(shove);
            PredVector hidden = hiddenVelocity(player, result, context, lastResult, travel, target);
            PredVector backedOff = backOffFromEdge(result, lastResult, hidden, target);
            // The source replay and carry derive input before the backoff. The later shove and its hidden
            // portion of that input must not be added to deltaMovement by this spatial projection.
            return trace.withPosition(backedOff.add(shove, "late shove after edge backoff"));
        }
        PredVector start = trace.position();
        PredVector hidden = hiddenVelocity(player, result, context, lastResult, start, end);
        PredVector backedOff = backOffFromEdge(result, lastResult, hidden, end);
        MovementTrace moved = trace.withVelocityShift(hidden);
        if (backedOff == hidden) return moved;
        Vec3 velocity = moved.preCollisionVelocity();
        Vec3 before = trace.preCollisionVelocity();
        boolean shortenedX = backedOff.x != hidden.x;
        boolean shortenedZ = backedOff.z != hidden.z;
        return moved.withPosition(backedOff)
                .withPreCollisionVelocity(
                        shortenedX || shortenedZ
                                ? new Vec3(
                                        shortenedX ? before.x : velocity.x,
                                        velocity.y,
                                        shortenedZ ? before.z : velocity.z)
                                : velocity);
    }

    private static boolean onEdge(PredictionResult result, PredictionResult lastResult) {
        StuckEdgeData lastOnEdge = lastResult == null
                ? null
                : lastResult.getSimulationContext().getWorldData().getSneak();
        return lastOnEdge != null && lastOnEdge.isOnEdge()
                || result.getSimulationContext().getWorldData().getSneak().isOnEdge();
    }

    /** {@code start} moved toward {@code end} by the velocity buggy sneaking may have hidden on the ground. */
    private static PredVector hiddenVelocity(
            CultPlayer player,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end) {
        StuckEdgeData lastOnEdge = lastResult == null
                ? null
                : lastResult.getSimulationContext().getWorldData().getSneak();

        if (onEdge(result, lastResult)) {
            float blockUnderPlayer =
                    BlockProperties.getFriction(player, context.getLastTickMainSupportingBlockData(), start);
            double friction = blockUnderPlayer * 0.91f;

            // So, this velocity is built up while on the ground, but due to buggy sneaking behaviour
            // we should assume that the player gained all their velocity when on the ground.
            DesyncStatus lastOnGround = result.getSimulationContext().getLastOnGround();
            result.getSimulationContext().getWorldData().setLastOnGround(DesyncStatus.UNKNOWN);
            // Get the speed when the player is on the ground
            // The hidden velocity built up before this tick's attack, under the modifier held then.
            double maxSpeed = context.getHorizontalInputAllowance(player, ClientSprintState.Outcome.PRIOR);
            result.getSimulationContext().getWorldData().setLastOnGround(lastOnGround);

            Vec3 from = result.getSimulationContext().getStart();
            SimpleCollisionBox oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(from.x, from.y, from.z, 0.6f, 0.6f);
            boolean isSlowed = Collisions.isEmpty(player, oldBox);

            // Thanks Physiq for this terminal velocity formula
            double multiplier = lastOnEdge != null && lastOnEdge.isZeroHorizBug()
                    ? 1.3
                    : isSlowed
                            ? player.compensatedEntities.getSelf().sneakingSpeed.value()
                            : 1;
            double sprintingMovementHidden = (friction * maxSpeed * multiplier) / (1 - friction);

            SimpleCollisionBox collide = new SimpleCollisionBox(start, start);

            SimpleCollisionBox hiddenMovement = lastResult == null ? null : lastResult.getRealitiesExtent();
            if (hiddenMovement != null) {
                hiddenMovement = hiddenMovement.copy();
                hiddenMovement.maxY = start.y;
                hiddenMovement.minY = start.y;
                collide = collide.union(hiddenMovement);
            }

            collide.expand(sprintingMovementHidden);
            collide.minY = start.y;
            collide.maxY = start.y;
            start = start.with(VectorUtils.cutBoxToVector(end, collide), "Sneaking hidden");
        }

        return start;
    }

    /** Player#maybeBackOffFromEdge: {@code start} shortened toward zero on each axis that would leave the edge. */
    private static PredVector backOffFromEdge(
            PredictionResult result, PredictionResult lastResult, PredVector start, Vec3 end) {
        StuckEdgeData lastOnEdge = lastResult == null
                ? null
                : lastResult.getSimulationContext().getWorldData().getSneak();
        StuckEdgeData thisEdge = result.getSimulationContext().getWorldData().getSneak();

        if (onEdge(result, lastResult)) {
            boolean isNorth = thisEdge.isNorth() || (lastOnEdge != null && lastOnEdge.isNorth());
            boolean isSouth = thisEdge.isSouth() || (lastOnEdge != null && lastOnEdge.isSouth());
            boolean isEast = thisEdge.isEast() || (lastOnEdge != null && lastOnEdge.isEast());
            boolean isWest = thisEdge.isWest() || (lastOnEdge != null && lastOnEdge.isWest());

            if (isNorth && start.z <= 0) { // -Z
                double bestZ = CultMath.clamp(end.z, start.z, 0);
                start = start.withZ(bestZ, "Sneaking");
            }
            if (isSouth && start.z >= 0) { // +Z
                double bestZ = CultMath.clamp(end.z, 0, start.z);
                start = start.withZ(bestZ, "Sneaking");
            }
            if (isWest && start.x <= 0) { // -X
                double bestX = CultMath.clamp(end.x, start.x, 0);
                start = start.withX(bestX, "Sneaking");
            }
            if (isEast && start.x >= 0) { // +X
                double bestX = CultMath.clamp(end.x, 0, start.x);
                start = start.withX(bestX, "Sneaking");
            }
        }

        return start;
    }
}
