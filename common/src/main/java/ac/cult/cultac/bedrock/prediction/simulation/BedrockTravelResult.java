package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import java.util.Objects;

record BedrockTravelResult(
        BedrockMovementResult movementResult,
        BedrockMobJumpComponentState mobJumpComponent
) {
    BedrockTravelResult {
        movementResult = Objects.requireNonNull(movementResult, "movementResult");
        mobJumpComponent = Objects.requireNonNull(mobJumpComponent, "mobJumpComponent");
    }
}
