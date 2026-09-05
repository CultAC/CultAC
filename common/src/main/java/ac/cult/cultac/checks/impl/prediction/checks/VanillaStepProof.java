package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.phys.Vec3;

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

        if (!canStandAtDestination(player, result)) {
            return true;
        }

        return !hasSourceProvenStepCandidate(player, result);
    }

    // Modern stepping can move beyond the shape that grounded the player.
    boolean canStandAtDestination(CultPlayer player, PredictionResult result) {
        return hasUnknownCollisionAtDestination(result)
                || hasCollisionDirectlyBelowDestination(player, result)
                || (result.getValidMovements().isCanStep()
                    && player.getClientVersion().usesModernEntityStepCollision()
                    && hasSourceProvenStepCandidate(player, result));
    }

    private boolean needsStepProof(CultPlayer player, PredictionResult result) {
        if (!result.getValidMovements().isCanStep()) {
            return false;
        }

        // Pre-1.14 clients can resolve a zero-Y step through legacy collision paths.
        return result.getTarget().y != 0.0D || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14);
    }

    private boolean hasUnknownCollisionAtDestination(PredictionResult result) {
        for (SimpleCollisionBox box : result.getCollideAxisData().getUnknown()) {
            if (box.isIntersected(result.getSimulationContext().getToMaximumExtent())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCollisionDirectlyBelowDestination(CultPlayer player, PredictionResult result) {
        Vec3 downwardCollision = Collisions.collide(
                player,
                result.getSimulationContext().getToCubeCollision(),
                0.0D,
                -SimpleCollisionBox.COLLISION_EPSILON,
                0.0D
        );
        return downwardCollision.y != -SimpleCollisionBox.COLLISION_EPSILON;
    }

    private boolean hasSourceProvenStepCandidate(CultPlayer player, PredictionResult result) {
        boolean requireSelectedStep = player.getClientVersion().usesModernEntityStepCollision()
                && !hasUnknownCollisionAtDestination(result)
                && !hasCollisionDirectlyBelowDestination(player, result);
        Vec3 target = result.getTarget();
        double desiredY = result.getInitialStartingVel().y;
        if (collidesToAcceptedY(player, result, target.x, desiredY, target.z, requireSelectedStep)) {
            return true;
        }

        SimpleCollisionBox attempted = result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        if (attempted == null) {
            return false;
        }

        double[] candidateX = axisSamples(target.x, attempted.minX, attempted.maxX);
        double[] candidateY = axisSamples(target.y, Math.min(desiredY, attempted.minY), Math.max(desiredY, attempted.maxY));
        double[] candidateZ = axisSamples(target.z, attempted.minZ, attempted.maxZ);
        for (double desiredX : candidateX) {
            for (double stepDesiredY : candidateY) {
                for (double desiredZ : candidateZ) {
                    if (collidesToAcceptedY(player, result, desiredX, stepDesiredY, desiredZ,
                            requireSelectedStep)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean collidesToAcceptedY(CultPlayer player, PredictionResult result,
                                        double desiredX, double desiredY, double desiredZ,
                                        boolean requireSelectedStep) {
        List<Collisions.Axis> order = requireSelectedStep
                ? (Math.abs(desiredX) < Math.abs(desiredZ)
                ? List.of(Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X)
                : List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z))
                : null;
        Vec3 collidedMovement = collideLikeClient(
                player,
                result,
                result.getSimulationContext().getFromMaximumExtent(),
                desiredX,
                desiredY,
                desiredZ,
                order,
                true
        );
        if (Math.abs(collidedMovement.y - result.getTarget().y) > STEP_Y_MATCH_EPSILON) {
            return false;
        }
        if (!requireSelectedStep) {
            return true;
        }
        if (result.getSimulationContext().isOnGround()
                && (desiredY >= 0.0D || collidedMovement.y == desiredY)) {
            return false;
        }

        Vec3 collisionWithoutStep = collideLikeClient(
                player,
                result,
                result.getSimulationContext().getFromMaximumExtent(),
                desiredX,
                desiredY,
                desiredZ,
                order,
                false
        );
        return Collisions.getHorizontalDistanceSqr(collidedMovement)
                > Collisions.getHorizontalDistanceSqr(collisionWithoutStep);
    }

    private double[] axisSamples(double packetDelta, double minAttemptedDelta, double maxAttemptedDelta) {
        if (Math.abs(maxAttemptedDelta - minAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
            if (Math.abs(packetDelta - minAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
                return new double[]{packetDelta};
            }
            return new double[]{packetDelta, minAttemptedDelta};
        }
        if (Math.abs(packetDelta - minAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
            return new double[]{packetDelta, maxAttemptedDelta};
        }
        if (Math.abs(packetDelta - maxAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
            return new double[]{packetDelta, minAttemptedDelta};
        }
        return new double[]{packetDelta, minAttemptedDelta, maxAttemptedDelta};
    }

    private Vec3 collideLikeClient(CultPlayer player, PredictionResult result, SimpleCollisionBox box,
                                   double desiredX, double desiredY, double desiredZ,
                                   List<Collisions.Axis> order, boolean allowStepping) {
        if (order == null) {
            return Collisions.collideWithAdditionalCollisionBoxes(player, box, desiredX, desiredY, desiredZ, Collections.emptyList());
        }

        return Collisions.collideWithAdditionalCollisionBoxes(
                player,
                box,
                desiredX,
                desiredY,
                desiredZ,
                order,
                allowStepping,
                Collections.emptyList(),
                result.getSimulationContext().getLastOnGround().determineOptimistically()
        );
    }
}
