package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityWorldCollisions;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.List;

// Validates steps against Entity#collide's collision-derived candidates.
final class VanillaStepProof {
    private static final double STEP_Y_MATCH_EPSILON = 1.0E-5D;
    private static final double AXIS_SAMPLE_EPSILON = 1.0E-9D;

    boolean isInvalidStep(CultPlayer player, PredictionResult result) {
        if (!needsStepProof(player, result)) {
            return false;
        }
        if (result.getProfileResult(BedrockPredictionResult.class) != null) {
            return false;
        }

        return !hasSourceProvenStepCandidate(player, result);
    }

    // Modern stepping can move beyond the shape that grounded the player.
    boolean canStandAtDestination(CultPlayer player, PredictionResult result) {
        return standsOnUnknownCollision(player, result)
                || hasCollisionDirectlyBelowDestination(player, result)
                || (result.getValidMovements().isCanStep() && hasSourceProvenStepCandidate(player, result));
    }

    private boolean needsStepProof(CultPlayer player, PredictionResult result) {
        if (!result.getValidMovements().isCanStep()) {
            return false;
        }

        return true;
    }

    private boolean hasUnknownCollisionAtDestination(PredictionResult result) {
        for (SimpleCollisionBox box : result.getCollideAxisData().getUnknown()) {
            if (box.isIntersected(result.getSimulationContext().getToMaximumExtent())) {
                return true;
            }
        }
        return false;
    }

    private boolean standsOnUnknownCollision(CultPlayer player, PredictionResult result) {
        if (!(result.getCollideAxisData() instanceof EntityWorldCollisions worlds)) {
            return hasUnknownCollisionAtDestination(result);
        }
        SimpleCollisionBox destination = result.getSimulationContext().getToCubeCollision();
        for (List<SimpleCollisionBox> colliders : worlds.colliders()) {
            if (anyDirectlyBelow(colliders, destination)) return true;
        }
        return false;
    }

    private boolean hasCollisionDirectlyBelowDestination(CultPlayer player, PredictionResult result) {
        return memo(player)
                .collidesDirectlyBelow(player, result.getSimulationContext().getToCubeCollision());
    }

    private static boolean anyDirectlyBelow(List<SimpleCollisionBox> colliders, SimpleCollisionBox box) {
        double probe = -SimpleCollisionBox.COLLISION_EPSILON;
        for (int i = 0; i < colliders.size(); i++) {
            if (colliders.get(i).collideY(box, probe, SimpleCollisionBox.AxisEpsilon.JAVA) != probe) return true;
        }
        return false;
    }

    private static StepCollisionMemo memo(CultPlayer player) {
        return player.checkManager.getSimulationProcessor().getStepCollisionMemo();
    }

    private boolean hasSourceProvenStepCandidate(CultPlayer player, PredictionResult result) {
        Boolean proven = result.getSourceProvenStep();
        if (proven == null) {
            proven = proveSourceStep(player, result);
            result.setSourceProvenStep(proven);
        }
        return proven;
    }

    private boolean proveSourceStep(CultPlayer player, PredictionResult result) {
        if (!(result.getCollideAxisData() instanceof EntityWorldCollisions worlds)) {
            return proveSourceStep(player, result, List.of(), !hasUnknownCollisionAtDestination(result));
        }
        // The client collides with each entity at the one position it holds it.
        for (List<SimpleCollisionBox> colliders : worlds.colliders()) {
            if (proveSourceStep(player, result, colliders, true)) return true;
        }
        return false;
    }

    private boolean proveSourceStep(
            CultPlayer player, PredictionResult result, List<SimpleCollisionBox> colliders, boolean selectable) {
        boolean requireSelectedStep = selectable
                && !hasCollisionDirectlyBelowDestination(player, result)
                && !anyDirectlyBelow(colliders, result.getSimulationContext().getToCubeCollision());
        Vec3 target = result.getTarget();
        double desiredY = result.getInitialStartingVel().y;
        if (collidesToAcceptedY(player, result, target.x, desiredY, target.z, requireSelectedStep, colliders)) {
            return true;
        }

        SimpleCollisionBox attempted = result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        if (attempted == null) {
            return false;
        }

        double[] candidateX = axisSamples(target.x, attempted.minX, attempted.maxX);
        double[] candidateY =
                axisSamples(target.y, Math.min(desiredY, attempted.minY), Math.max(desiredY, attempted.maxY));
        double[] candidateZ = axisSamples(target.z, attempted.minZ, attempted.maxZ);
        for (double desiredX : candidateX) {
            for (double stepDesiredY : candidateY) {
                for (double desiredZ : candidateZ) {
                    if (collidesToAcceptedY(
                            player, result, desiredX, stepDesiredY, desiredZ, requireSelectedStep, colliders)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean collidesToAcceptedY(
            CultPlayer player,
            PredictionResult result,
            double desiredX,
            double desiredY,
            double desiredZ,
            boolean requireSelectedStep,
            List<SimpleCollisionBox> colliders) {
        // Entity#move scales the delta by the stuck speed before Entity#collide; the target and desired axes are
        // divided by it.
        Vec3 stuck = result.getSimulationContext().getLastStuckSpeed();
        desiredX *= stuck.x;
        desiredY *= stuck.y;
        desiredZ *= stuck.z;
        List<Collisions.Axis> order = requireSelectedStep
                ? (Math.abs(desiredX) < Math.abs(desiredZ)
                        ? List.of(Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X)
                        : List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z))
                : null;
        Vec3 collidedMovement;
        Vec3 collisionWithoutStep = null;
        if (order != null && !player.isBedrockMovement()) {
            boolean onGroundForStep =
                    result.getSimulationContext().getLastOnGround().determineOptimistically();
            Collisions.SteppedCollision collision = colliders.isEmpty()
                    ? memo(player)
                            .steppedCollision(
                                    player,
                                    result.getSimulationContext().getFromActualPose(),
                                    desiredX,
                                    desiredY,
                                    desiredZ,
                                    order,
                                    onGroundForStep)
                    : Collisions.collideWithStepComparison(
                            player,
                            result.getSimulationContext().getFromActualPose(),
                            desiredX,
                            desiredY,
                            desiredZ,
                            order,
                            colliders,
                            onGroundForStep);
            collidedMovement = collision.movement();
            collisionWithoutStep = collision.withoutStep();
        } else {
            collidedMovement = collideLikeClient(
                    player,
                    result,
                    result.getSimulationContext().getFromActualPose(),
                    desiredX,
                    desiredY,
                    desiredZ,
                    order,
                    true,
                    colliders);
        }
        if (Math.abs(collidedMovement.y - result.getTarget().y * stuck.y) > STEP_Y_MATCH_EPSILON) {
            return false;
        }
        if (!requireSelectedStep) {
            return true;
        }
        if (result.getSimulationContext().isOnGround() && (desiredY >= 0.0D || collidedMovement.y == desiredY)) {
            return false;
        }

        if (collisionWithoutStep == null) {
            collisionWithoutStep = collideLikeClient(
                    player,
                    result,
                    result.getSimulationContext().getFromActualPose(),
                    desiredX,
                    desiredY,
                    desiredZ,
                    order,
                    false,
                    colliders);
        }
        return Collisions.getHorizontalDistanceSqr(collidedMovement)
                > Collisions.getHorizontalDistanceSqr(collisionWithoutStep);
    }

    private double[] axisSamples(double packetDelta, double minAttemptedDelta, double maxAttemptedDelta) {
        if (Math.abs(maxAttemptedDelta - minAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
            if (Math.abs(packetDelta - minAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
                return new double[] {packetDelta};
            }
            return new double[] {packetDelta, minAttemptedDelta};
        }
        if (Math.abs(packetDelta - minAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
            return new double[] {packetDelta, maxAttemptedDelta};
        }
        if (Math.abs(packetDelta - maxAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
            return new double[] {packetDelta, minAttemptedDelta};
        }
        return new double[] {packetDelta, minAttemptedDelta, maxAttemptedDelta};
    }

    private Vec3 collideLikeClient(
            CultPlayer player,
            PredictionResult result,
            SimpleCollisionBox box,
            double desiredX,
            double desiredY,
            double desiredZ,
            List<Collisions.Axis> order,
            boolean allowStepping,
            List<SimpleCollisionBox> colliders) {
        if (order == null) {
            return Collisions.collideWithAdditionalCollisionBoxes(player, box, desiredX, desiredY, desiredZ, colliders);
        }

        return Collisions.collideWithAdditionalCollisionBoxes(
                player,
                box,
                desiredX,
                desiredY,
                desiredZ,
                order,
                allowStepping,
                colliders,
                result.getSimulationContext().getLastOnGround().determineOptimistically());
    }
}
