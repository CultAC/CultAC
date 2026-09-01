package ac.grim.grimac.bedrock.prediction.model;

import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.input.BedrockPoseInputData;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;

public enum BedrockBoundingBoxMode {
    DEFAULT,
    SNEAKING,
    HORIZONTAL;

    public static BedrockBoundingBoxMode initial(BedrockInputFrame frame) {
        if (BedrockPoseInputData.committedNonGlidingLowPose(frame)
                || glidingStarts(frame)) {
            return HORIZONTAL;
        }
        return frame.sneaking() ? SNEAKING : DEFAULT;
    }

    public static BedrockBoundingBoxMode resolve(BedrockMovementState previous, BedrockInputFrame frame) {
        boolean gliding = !glidingStops(frame) && (previous.gliding() || glidingStarts(frame));
        if (gliding || BedrockPoseInputData.committedNonGlidingLowPose(frame)) {
            return HORIZONTAL;
        }
        return frame.sneaking() ? SNEAKING : DEFAULT;
    }

    private static boolean glidingStarts(BedrockInputFrame frame) {
        return BedrockPoseInputData.has(frame, BedrockPoseInputData.GLIDING)
            || BedrockPoseInputData.has(frame, BedrockPoseInputData.START_GLIDING)
            || BedrockPoseInputData.has(frame, BedrockPoseInputData.START_GLIDING_ACTION);
    }

    private static boolean glidingStops(BedrockInputFrame frame) {
        return BedrockPoseInputData.has(frame, BedrockPoseInputData.STOP_GLIDING)
            || BedrockPoseInputData.has(frame, BedrockPoseInputData.STOP_GLIDING_ACTION);
    }
}
