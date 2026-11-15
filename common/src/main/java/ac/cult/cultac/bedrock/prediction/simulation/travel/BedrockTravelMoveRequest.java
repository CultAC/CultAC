package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockSneakEdgeMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

public final class BedrockTravelMoveRequest {
    private BedrockTravelMoveRequest() {}

    private static final float MAX_MOVE_LENGTH = 16.0F;

    /** The client moves at most 16 blocks a tick: a longer move keeps its direction and is cut to 16. */
    static Vec3d clampedMove(Vec3d move) {
        float x = (float) move.x();
        float y = (float) move.y();
        float z = (float) move.z();
        float lengthSquared = z * z + (y * y + x * x);
        if (!(lengthSquared > MAX_MOVE_LENGTH * MAX_MOVE_LENGTH)) {
            return move;
        }
        float length = (float) Math.sqrt(lengthSquared);
        return new Vec3d(x / length * MAX_MOVE_LENGTH, y / length * MAX_MOVE_LENGTH, z / length * MAX_MOVE_LENGTH);
    }

    public static Vec3d requestedPosition(BedrockMovementState previous, Vec3d move) {
        return previous.physicalFeetPosition().add(move);
    }

    public static BedrockMoveRequest applySneakMovement(
            BedrockTravelInput input, BedrockFrameFacts facts, BedrockMoveRequest moveRequest) {
        return BedrockSneakEdgeMovement.applyBeforeCollision(
                input.previousState(),
                input.inputFrame().sneaking(),
                facts.movementDimensions(),
                input.options().maxUpStep(),
                moveRequest,
                facts.blockCollisionWorld());
    }
}
