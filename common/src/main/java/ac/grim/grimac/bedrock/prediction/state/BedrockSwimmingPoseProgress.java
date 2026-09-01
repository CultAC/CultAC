package ac.grim.grimac.bedrock.prediction.state;

import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.input.BedrockPoseInputData;

public final class BedrockSwimmingPoseProgress {
    private static final float SWIM_AMOUNT_STEP = 0.1F;

    private BedrockSwimmingPoseProgress() {
    }

    public static boolean initialSwimming(BedrockInputFrame frame) {
        return BedrockPoseInputData.has(frame, BedrockPoseInputData.START_SWIMMING)
            && !BedrockPoseInputData.has(frame, BedrockPoseInputData.STOP_SWIMMING);
    }

    public static double initialSwimAmount(BedrockInputFrame frame) {
        return initialSwimming(frame) || BedrockPoseInputData.committedSwimming(frame) || initialHorizontalPose(frame)
            ? SWIM_AMOUNT_STEP
            : 0.0D;
    }

    public static boolean initialHorizontalPose(BedrockInputFrame frame) {
        return BedrockPoseInputData.committedHorizontalPose(frame);
    }

    public static double nextSwimAmount(
        boolean swimPoseActive,
        double previousSwimAmount
    ) {
        return nextSwimAmount(previousSwimAmount, swimPoseActive);
    }

    public static double nextSwimAmount(double previousSwimAmount, boolean swimPoseActive) {
        float next = (float) previousSwimAmount + (swimPoseActive ? SWIM_AMOUNT_STEP : -SWIM_AMOUNT_STEP);
        if (next <= 0.0D) {
            return 0.0D;
        }
        if (next >= 1.0D) {
            return 1.0D;
        }
        return next;
    }
}
