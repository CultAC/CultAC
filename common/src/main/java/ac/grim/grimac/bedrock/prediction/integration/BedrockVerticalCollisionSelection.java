package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.BedrockVerticalCollisionVerdict;
import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockCollisionSweep;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import java.util.List;

final class BedrockVerticalCollisionSelection {
    private BedrockVerticalCollisionSelection() {
    }

    static List<BedrockProfileState.Entry> apply(
            List<BedrockProfileState.Entry> entries,
            BedrockMovementResult movementResult,
            boolean claimedCollision,
            BedrockVerticalCollisionVerdict verdict
    ) {
        if (entries.isEmpty() || movementResult == null || verdict != BedrockVerticalCollisionVerdict.LEGAL) {
            return entries;
        }
        double requestedY = movementResult.rawPredictedPhysicalFeetPosition().y()
                - movementResult.previousState().physicalFeetPosition().y();
        return entries.stream()
                .map(entry -> entry.withState(select(
                        entry.state(),
                        movementResult.previousState(),
                        claimedCollision,
                        requestedY)))
                .toList();
    }

    static BedrockMovementState select(
            BedrockMovementState state,
            BedrockMovementState previous,
            boolean claimedCollision,
            double requestedY
    ) {
        BedrockCollisionFlags current = state.collisionFlags();
        boolean onGround;
        boolean verticalCollisionBelow;
        if (claimedCollision) {
            onGround = current.onGround()
                    || current.verticalCollisionBelow()
                    || requestedY < 0.0D;
            verticalCollisionBelow = onGround;
        } else {
            onGround = previous.collisionFlags().onGround()
                    && Math.abs(requestedY) <= BedrockCollisionSweep.EPSILON;
            verticalCollisionBelow = false;
        }

        BedrockCollisionFlags selected = new BedrockCollisionFlags(
                onGround,
                current.horizontalCollision(),
                claimedCollision,
                current.horizontalBlockContact(),
                current.liquidClimbOut(),
                verticalCollisionBelow,
                current.xCollision(),
                current.zCollision());
        Medium branch = selectedBranch(state.movementBranch(), onGround);
        if (selected.equals(current) && branch == state.movementBranch()) {
            return state;
        }
        return state.withVelocityAndCollisionFlags(state.velocity(), selected)
                .withMovementBranch(branch);
    }

    private static Medium selectedBranch(Medium current, boolean onGround) {
        if (current == Medium.WATER || current == Medium.LAVA) {
            return current;
        }
        return onGround ? Medium.GROUND : Medium.AIR;
    }
}
