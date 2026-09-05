package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockSneakEdgeMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

public final class BedrockTravelMoveRequest {
    private BedrockTravelMoveRequest() {
    }

    public static Vec3d requestedPosition(BedrockMovementState previous, Vec3d move) {
        return previous.physicalFeetPosition().add(move);
    }

    public static Vec3d applySneakMovement(
        BedrockTravelInput input,
        BedrockFrameFacts facts,
        BedrockMoveRequest moveRequest
    ) {
        return BedrockSneakEdgeMovement.applyBeforeCollision(
            input.previousState(),
            input.inputFrame().sneaking(),
            moveRequest.move(),
            moveRequest.requestedPosition(),
            facts.blockCollisionWorld()
        );
    }
}
