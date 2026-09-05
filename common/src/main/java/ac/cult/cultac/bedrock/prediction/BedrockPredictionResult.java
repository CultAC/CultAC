package ac.cult.cultac.bedrock.prediction;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

public record BedrockPredictionResult(
        BedrockMovementResult movementResult,
        BedrockMovementObservation observation,
        BedrockMovementState nextTickBaseState,
        BedrockMobJumpComponentState nextTickBaseMobJumpComponent,
        BedrockVerticalCollisionVerdict verticalCollisionVerdict,
        boolean collisionClaimMatchesCandidate
) {
    public BedrockPredictionResult {
        nextTickBaseMobJumpComponent = nextTickBaseMobJumpComponent == null
                ? BedrockMobJumpComponentState.DEFAULT
                : nextTickBaseMobJumpComponent;
        verticalCollisionVerdict = verticalCollisionVerdict == null
                ? BedrockVerticalCollisionVerdict.LEGAL
                : verticalCollisionVerdict;
    }

    public BedrockPredictionResult withObservation(BedrockMovementObservation observation) {
        return new BedrockPredictionResult(
                movementResult,
                observation,
                nextTickBaseState,
                nextTickBaseMobJumpComponent,
                verticalCollisionVerdict,
                collisionClaimMatchesCandidate);
    }

    public BedrockPredictionResult withNextTickBaseState(BedrockMovementState nextTickBaseState) {
        return new BedrockPredictionResult(
                movementResult,
                observation,
                nextTickBaseState,
                nextTickBaseMobJumpComponent,
                verticalCollisionVerdict,
                collisionClaimMatchesCandidate);
    }
}
