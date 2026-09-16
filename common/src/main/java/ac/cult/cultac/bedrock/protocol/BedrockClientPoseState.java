package ac.cult.cultac.bedrock.protocol;

public record BedrockClientPoseState(
        boolean sneaking,
        boolean crawling,
        boolean swimming,
        boolean spinning,
        boolean sleeping,
        boolean gliding
) {
    public BedrockClientPoseState(boolean sneaking, boolean crawling, boolean swimming) {
        this(sneaking, crawling, swimming, false, false, false);
    }
    public BedrockClientPoseState(boolean sneaking, boolean crawling, boolean swimming, boolean spinning, boolean sleeping) {
        this(sneaking, crawling, swimming, spinning, sleeping, false);
    }

    public static final BedrockClientPoseState STANDING =
            new BedrockClientPoseState(false, false, false);

    public boolean lowHeightPose() {
        return crawling || swimming;
    }

    public BedrockClientPoseState withSneaking(boolean sneaking) {
        return new BedrockClientPoseState(sneaking, crawling, swimming, spinning, sleeping, gliding);
    }

    public BedrockClientPoseState withCrawling(boolean crawling) {
        return new BedrockClientPoseState(sneaking, crawling, swimming, spinning, sleeping, gliding);
    }

    public BedrockClientPoseState withSwimming(boolean swimming) {
        return new BedrockClientPoseState(sneaking, crawling, swimming, spinning, sleeping, gliding);
    }

    public BedrockClientPoseState withSpinning(boolean value) {
        return new BedrockClientPoseState(sneaking, crawling, swimming, value, sleeping, gliding);
    }

    public BedrockClientPoseState withSleeping(boolean value) {
        return new BedrockClientPoseState(sneaking, crawling, swimming, spinning, value, gliding);
    }
    public BedrockClientPoseState withGliding(boolean value) {
        return new BedrockClientPoseState(sneaking, crawling, swimming, spinning, sleeping, value);
    }
}
