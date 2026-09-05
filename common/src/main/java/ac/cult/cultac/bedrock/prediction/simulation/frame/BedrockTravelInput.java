package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import java.util.Objects;

public record BedrockTravelInput(
        BedrockMovementState previousState,
        BedrockInputFrame inputFrame,
        BedrockInputIntent inputIntent,
        BedrockWorldSnapshot worldSnapshot,
        ScaffoldingVerticalBranch scaffoldingVerticalBranch,
        Vec3d startingVelocity,
        BedrockTravelOptions options
) {
    public BedrockTravelInput {
        previousState = Objects.requireNonNull(previousState, "previousState");
        inputFrame = Objects.requireNonNull(inputFrame, "inputFrame");
        inputIntent = Objects.requireNonNull(inputIntent, "inputIntent");
        worldSnapshot = Objects.requireNonNull(worldSnapshot, "worldSnapshot");
        scaffoldingVerticalBranch = Objects.requireNonNull(scaffoldingVerticalBranch, "scaffoldingVerticalBranch");
        startingVelocity = Objects.requireNonNull(startingVelocity, "startingVelocity");
        options = Objects.requireNonNull(options, "options");
    }

    public enum ScaffoldingVerticalBranch {
        SOURCE,
        DESCEND
    }
}
