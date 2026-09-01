package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.BedrockVerticalCollisionVerdict;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.CollideAxisData;

final class BedrockVerticalCollisionClassifier {
    private static final double EPSILON = 0.001D;

    private BedrockVerticalCollisionClassifier() {
    }

    static BedrockVerticalCollisionVerdict classify(
            PredictionResult result,
            BedrockMovementState predictedState,
            boolean claimedCollision
    ) {
        if (result == null) {
            return BedrockVerticalCollisionVerdict.LEGAL;
        }
        ValidMovements validMovements = result.getValidMovements();
        BedrockVerticalCollisionVerdict verdict = classify(
                claimedCollision,
                result.getCollideAxisData(),
                validMovements == null ? null : validMovements.getCollisionIgnoredMaxStartingVelExtents(),
                result.getTarget() == null ? 0.0D : result.getTarget().y,
                validMovements != null && validMovements.isCanStep());
        if (claimedCollision
                && verdict == BedrockVerticalCollisionVerdict.MANUFACTURED_COLLISION
                && predictedState != null
                && predictedState.collisionFlags().verticalCollision()) {
            return BedrockVerticalCollisionVerdict.LEGAL;
        }
        return verdict;
    }

    static BedrockVerticalCollisionVerdict classify(
            boolean claimedCollision,
            CollideAxisData collision,
            SimpleCollisionBox attemptedMovement,
            double targetY,
            boolean canStep
    ) {
        if (collision == null) {
            return BedrockVerticalCollisionVerdict.LEGAL;
        }

        if (collision.getUnknown() != null && !collision.getUnknown().isEmpty()) {
            return BedrockVerticalCollisionVerdict.LEGAL;
        }

        CollideAxisData.CollideResult up = collision.getYPos();
        CollideAxisData.CollideResult down = collision.getYNeg();
        boolean canCollideUp = likely(up);
        boolean canCollideDown = likely(down);

        if (claimedCollision) {
            return canCollideUp || canCollideDown || canStep
                    ? BedrockVerticalCollisionVerdict.LEGAL
                    : BedrockVerticalCollisionVerdict.MANUFACTURED_COLLISION;
        }

        if (attemptedMovement == null) {
            return BedrockVerticalCollisionVerdict.LEGAL;
        }

        boolean requiresDownCollision = canCollideDown
                && attemptedMovement.maxY + EPSILON < targetY
                && down.getResult() + EPSILON >= targetY;
        boolean requiresUpCollision = canCollideUp
                && attemptedMovement.minY - EPSILON > targetY
                && up.getResult() - EPSILON <= targetY;
        return requiresDownCollision || requiresUpCollision
                ? BedrockVerticalCollisionVerdict.MANUFACTURED_NON_COLLISION
                : BedrockVerticalCollisionVerdict.LEGAL;
    }

    static boolean claimMatchesCandidate(boolean claimedCollision, BedrockMovementState state) {
        return state != null && claimedCollision == state.collisionFlags().verticalCollision();
    }

    private static boolean likely(CollideAxisData.CollideResult result) {
        return result != null && result.isLikelyCollide();
    }
}
