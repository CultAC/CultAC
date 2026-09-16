package ac.cult.cultac.bedrock.prediction.input;

public final class BedrockPoseInputData {
    public static final String ACTOR_POSE_SNAPSHOT = "ACTOR_POSE_SNAPSHOT";
    public static final String ACTOR_SNEAKING = "ACTOR_SNEAKING";
    public static final String ACTOR_SLEEPING = "ACTOR_SLEEPING";
    public static final String GLIDING = "GLIDING";
    public static final String HORIZONTAL_POSE = "HORIZONTAL_POSE";
    public static final String START_CRAWLING = "START_CRAWLING";
    public static final String START_FLYING = "START_FLYING";
    public static final String START_GLIDING = "START_GLIDING";
    public static final String START_GLIDING_ACTION = "START_GLIDING_ACTION";
    public static final String START_SNEAKING = "START_SNEAKING";
    public static final String START_SWIMMING = "START_SWIMMING";
    public static final String STOP_CRAWLING = "STOP_CRAWLING";
    public static final String STOP_FLYING = "STOP_FLYING";
    public static final String STOP_GLIDING = "STOP_GLIDING";
    public static final String STOP_GLIDING_ACTION = "STOP_GLIDING_ACTION";
    public static final String STOP_SNEAKING = "STOP_SNEAKING";
    public static final String STOP_SWIMMING = "STOP_SWIMMING";
    public static final String SWIMMING = "SWIMMING";

    private BedrockPoseInputData() {
    }

    public static boolean committedHorizontalPose(BedrockInputFrame frame) {
        return has(frame, HORIZONTAL_POSE);
    }

    public static boolean committedSwimming(BedrockInputFrame frame) {
        return has(frame, SWIMMING);
    }

    public static boolean committedNonGlidingLowPose(BedrockInputFrame frame) {
        return committedHorizontalPose(frame) || committedSwimming(frame);
    }

    public static boolean has(BedrockInputFrame frame, String inputData) {
        return frame != null && frame.inputData().contains(inputData);
    }
}
