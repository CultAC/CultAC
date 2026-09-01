package ac.grim.grimac.bedrock.prediction.simulation;

import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
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
