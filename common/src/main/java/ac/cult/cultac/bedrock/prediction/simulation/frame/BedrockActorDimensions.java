package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockPoseInputData;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockActorDimensions {
    private static final double BEDROCK_LOW_POSE_HEIGHT = 0.6F;

    private BedrockActorDimensions() {
    }

    public static PlayerDimensionsState committedMovementDimensions(
        BedrockMovementState previousState,
        BedrockMovementContext context,
        BedrockInputFrame currentFrame
    ) {
        return resolve(previousState, context, currentFrame).dimensions();
    }

    public static Resolved resolve(
        BedrockMovementState previousState,
        BedrockMovementContext context,
        BedrockInputFrame currentFrame
    ) {
        return resolve(previousState, context, currentFrame, previousState.riptideSpinActive(), false);
    }

    static Resolved resolve(
        BedrockMovementState previousState,
        BedrockMovementContext context,
        BedrockInputFrame currentFrame,
        boolean spinActive,
        boolean spinAttackStarted
    ) {
        PlayerDimensionsState currentDimensions = context.playerDimensionsState();
        if (previousState.isVehicle()) {
            return new Resolved(BedrockBoundingBoxMode.DEFAULT, currentDimensions);
        }
        BedrockBoundingBoxMode mode = spinActive ? BedrockBoundingBoxMode.HORIZONTAL
            : BedrockBoundingBoxMode.resolve(previousState, currentFrame);
        var definition = previousState.collisionDefinition();
        if (definition != null) {
            var intent = currentFrame.intent();
            boolean swimming = BedrockSwimmingMovement.initial(previousState, context, currentFrame)
                .afterActions(intent, context.inWater()).nextActorSwimming();
            boolean gliding = BedrockGlidingTravelMovement.resolve(previousState, intent, context)
                .activeAfterActions();
            boolean crawling = BedrockPoseInputData.crawlingAfterActions(currentFrame, previousState.horizontalPose());
            mode = spinActive || swimming || gliding || crawling ? BedrockBoundingBoxMode.HORIZONTAL
                : BedrockPoseInputData.sneakingAfterActions(currentFrame, currentFrame.sneaking())
                    ? BedrockBoundingBoxMode.SNEAKING : BedrockBoundingBoxMode.DEFAULT;
            if (!resizeRequested(previousState, currentFrame, spinActive, spinAttackStarted)
                    && !BedrockGlidingTravelMovement.requestsResize(previousState, intent, context)) {
                return new Resolved(mode, previousState.playerDimensions());
            }
            double height = switch (mode) {
                case HORIZONTAL -> definition.width();
                case SNEAKING -> (double) 1.49F;
                case DEFAULT -> definition.height();
            };
            return new Resolved(mode, new PlayerDimensionsState(definition.width(), height));
        }
        // A local launch requests a resize; synchronized spin metadata alone does not.
        if (spinAttackStarted) {
            return horizontalPoseDimensions(currentDimensions);
        }
        // Geyser supplies collision dimensions separately from pose flags.
        // Preserve the acknowledged size until new dimensions arrive.
        if (previousState.explicitPlayerDimensions()) {
            return new Resolved(mode, previousState.playerDimensions());
        }
        if (mode == BedrockBoundingBoxMode.HORIZONTAL) {
            return horizontalPoseDimensions(currentDimensions);
        }
        return new Resolved(mode, currentDimensions);
    }

    private static boolean resizeRequested(BedrockMovementState previous, BedrockInputFrame frame,
                                           boolean spinning, boolean spinStarted) {
        if (spinStarted || spinning != previous.riptideSpinActive()) return true;
        return frame.inputData().stream().anyMatch(action -> switch (action) {
            case BedrockPoseInputData.START_GLIDING, BedrockPoseInputData.STOP_GLIDING,
                    BedrockPoseInputData.START_GLIDING_ACTION, BedrockPoseInputData.STOP_GLIDING_ACTION,
                    BedrockPoseInputData.START_SWIMMING, BedrockPoseInputData.STOP_SWIMMING,
                    BedrockPoseInputData.START_CRAWLING, BedrockPoseInputData.STOP_CRAWLING,
                    BedrockPoseInputData.START_SNEAKING, BedrockPoseInputData.STOP_SNEAKING,
                    "START_SPIN_ATTACK", "STOP_SPIN_ATTACK" -> true;
            default -> false;
        });
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
