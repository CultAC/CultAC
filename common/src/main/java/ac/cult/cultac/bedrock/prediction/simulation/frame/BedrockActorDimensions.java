package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

public final class BedrockActorDimensions {
    private static final double BEDROCK_LOW_POSE_HEIGHT = 0.6F;

    private BedrockActorDimensions() {
    }

    public static PlayerDimensionsState committedMovementDimensions(
        BedrockMovementState previousState,
        PlayerDimensionsState currentDimensions,
        BedrockInputFrame currentFrame
    ) {
        return resolve(previousState, currentDimensions, currentFrame).dimensions();
    }

    public static Resolved resolve(
        BedrockMovementState previousState,
        PlayerDimensionsState currentDimensions,
        BedrockInputFrame currentFrame
    ) {
        if (previousState.riptideSpinActive()) {
            return spinAttackDimensions(previousState.playerDimensions());
        }
        BedrockBoundingBoxMode mode = BedrockBoundingBoxMode.resolve(previousState, currentFrame);
        // Geyser supplies collision dimensions separately from pose flags.
        // Preserve the acknowledged size until new dimensions arrive.
        if (previousState.explicitPlayerDimensions()) {
            return new Resolved(mode, previousState.playerDimensions());
        }
        if (mode == BedrockBoundingBoxMode.HORIZONTAL) {
            return horizontalPoseDimensions(currentDimensions);
        }
        if (previousState.boundingBoxMode() == BedrockBoundingBoxMode.HORIZONTAL) {
            return new Resolved(mode, currentDimensions);
        }
        return new Resolved(mode, currentDimensions);
    }

    static Resolved resolve(
        BedrockMovementState previousState,
        PlayerDimensionsState currentDimensions,
        BedrockInputFrame currentFrame,
        boolean spinActive
    ) {
        if (spinActive) {
            return spinAttackDimensions(currentDimensions);
        }
        if (!previousState.riptideSpinActive()) {
            return resolve(previousState, currentDimensions, currentFrame);
        }
        return new Resolved(
            BedrockBoundingBoxMode.resolve(previousState, currentFrame),
            currentDimensions
        );
    }

    private static Resolved spinAttackDimensions(PlayerDimensionsState dimensions) {
        return horizontalPoseDimensions(dimensions);
    }

    private static Resolved horizontalPoseDimensions(PlayerDimensionsState dimensions) {
        return new Resolved(
            BedrockBoundingBoxMode.HORIZONTAL,
            new PlayerDimensionsState(dimensions.width(), BEDROCK_LOW_POSE_HEIGHT)
        );
    }

    public record Resolved(BedrockBoundingBoxMode mode, PlayerDimensionsState dimensions) {
    }
}
