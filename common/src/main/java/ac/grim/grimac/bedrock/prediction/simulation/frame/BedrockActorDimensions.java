package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;

public final class BedrockActorDimensions {
    private static final double DEFAULT_CAMERA_OFFSET = 1.6200100183486938D;
    private static final double LOW_POSE_CAMERA_OFFSET = 0.4000000059604645D;
    private static final double LOW_POSE_HEIGHT = 0.6D;
    private static final double BEDROCK_LOW_POSE_HEIGHT = 0.6F;

    private BedrockActorDimensions() {
    }

    public static double cameraOffset(PlayerDimensionsState dimensions) {
        if (dimensions.height() <= LOW_POSE_HEIGHT + 1.0E-6D) {
            return LOW_POSE_CAMERA_OFFSET;
        }
        return Math.min(DEFAULT_CAMERA_OFFSET, Math.max(LOW_POSE_CAMERA_OFFSET, dimensions.height() - 0.18D));
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
        if (mode == BedrockBoundingBoxMode.HORIZONTAL) {
            return horizontalPoseDimensions(currentDimensions);
        }
        if (previousState.boundingBoxMode() == BedrockBoundingBoxMode.HORIZONTAL) {
            return new Resolved(mode, currentDimensions);
        }
        if (previousState.explicitPlayerDimensions()) {
            return new Resolved(mode, previousState.playerDimensions());
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
