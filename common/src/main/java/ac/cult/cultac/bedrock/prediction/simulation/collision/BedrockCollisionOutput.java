package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import java.util.Objects;

public record BedrockCollisionOutput(
    BedrockEntityMove.Result blockMove,
    BedrockClimbableContact nextClimbableContact,
    BedrockMoveRequest moveRequest,
    WorldCollisionBox collisionFetchBox
) {
    public BedrockCollisionOutput {
        blockMove = Objects.requireNonNull(blockMove, "blockMove");
        nextClimbableContact = Objects.requireNonNull(nextClimbableContact, "nextClimbableContact");
        moveRequest = Objects.requireNonNull(moveRequest, "moveRequest");
    }
}
