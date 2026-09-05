package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionOutput;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockMoveSystems;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameSystems;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockPostMoveResult;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockPostMoveSystems;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockTravelPlan;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockVelocitySystems;

final class BedrockTravelEngine {
    static final BedrockTravelEngine INSTANCE = new BedrockTravelEngine();

    private BedrockTravelEngine() {
    }

    BedrockMovementResult move(BedrockTravelInput input) {
        return travel(input).movementResult();
    }

    BedrockMovementResult move(BedrockTravelInput input, BedrockMobJumpComponentState mobJumpComponent) {
        return travel(input, mobJumpComponent).movementResult();
    }

    BedrockTravelResult travel(BedrockTravelInput input) {
        return travel(input, BedrockMobJumpComponentState.DEFAULT);
    }

    BedrockTravelResult travel(BedrockTravelInput input, BedrockMobJumpComponentState mobJumpComponent) {
        BedrockFrameState frame = BedrockFrameSystems.prepare(input, mobJumpComponent);
        BedrockTravelPlan plan = BedrockVelocitySystems.plan(frame);
        BedrockCollisionOutput collision = BedrockMoveSystems.move(plan);
        BedrockPostMoveResult postMove = BedrockPostMoveSystems.postMove(plan, collision);
        BedrockMovementResult result = BedrockMovementResultBuilder.build(plan, collision, postMove);
        return new BedrockTravelResult(result, frame.mobJumpComponent());
    }
}
