package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.bedrock.prediction.BedrockPredictionResult;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.nmsutil.Collisions;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.phys.Vec3;

// Validates steps against Entity#collide's collision-derived candidates.
final class VanillaStepProof {
    private static final double STEP_Y_MATCH_EPSILON = 1.0E-5D;
    private static final double AXIS_SAMPLE_EPSILON = 1.0E-9D;

    boolean isInvalidStep(GrimPlayer player, PredictionResult result) {
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

    // Unknown destination collisions cannot disprove the ground claim.
    boolean canStandAtDestination(GrimPlayer player, PredictionResult result) {
        return hasUnknownCollisionAtDestination(result)
                || hasCollisionDirectlyBelowDestination(player, result);
    }

    private boolean needsStepProof(GrimPlayer player, PredictionResult result) {
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

    private boolean hasCollisionDirectlyBelowDestination(GrimPlayer player, PredictionResult result) {
        Vec3 downwardCollision = Collisions.collide(
                player,
                result.getSimulationContext().getToCubeCollision(),
                0.0D,
                -SimpleCollisionBox.COLLISION_EPSILON,
                0.0D
        );
        return downwardCollision.y != -SimpleCollisionBox.COLLISION_EPSILON;
    }

    private boolean hasSourceProvenStepCandidate(GrimPlayer player, PredictionResult result) {
        Vec3 target = result.getTarget();
        double desiredY = result.getInitialStartingVel().y;
        if (collidesToAcceptedY(player, result, target.x, desiredY, target.z)) {
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
                    if (collidesToAcceptedY(player, result, desiredX, stepDesiredY, desiredZ)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean collidesToAcceptedY(GrimPlayer player, PredictionResult result, double desiredX, double desiredY, double desiredZ) {
        Vec3 collidedMovement = collideLikeClient(
                player,
                result,
                result.getSimulationContext().getFromMaximumExtent(),
                desiredX,
                desiredY,
                desiredZ,
                null,
                true
        );
        return Math.abs(collidedMovement.y - result.getTarget().y) <= STEP_Y_MATCH_EPSILON;
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

    private Vec3 collideLikeClient(GrimPlayer player, PredictionResult result, SimpleCollisionBox box,
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
