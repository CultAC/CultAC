package ac.grim.grimac.bedrock.prediction.simulation.collision;

import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.grim.grimac.bedrock.prediction.world.BedrockClimbableContact;
import java.util.Objects;

public record BedrockCollisionOutput(
    BedrockEntityMove.Result blockMove,
    BedrockClimbableContact nextClimbableContact,
    BedrockMoveRequest moveRequest
) {
    public BedrockCollisionOutput {
        blockMove = Objects.requireNonNull(blockMove, "blockMove");
        nextClimbableContact = Objects.requireNonNull(nextClimbableContact, "nextClimbableContact");
        moveRequest = Objects.requireNonNull(moveRequest, "moveRequest");
    }
}
