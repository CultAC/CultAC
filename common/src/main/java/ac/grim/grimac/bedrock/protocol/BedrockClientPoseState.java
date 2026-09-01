package ac.grim.grimac.bedrock.protocol;

public record BedrockClientPoseState(
        boolean sneaking,
        boolean crawling,
        boolean swimming
) {
    public static final BedrockClientPoseState STANDING =
            new BedrockClientPoseState(false, false, false);

    public boolean lowHeightPose() {
        return crawling || swimming;
    }

    public BedrockClientPoseState withSneaking(boolean sneaking) {
        return new BedrockClientPoseState(sneaking, crawling, swimming);
    }

    public BedrockClientPoseState withCrawling(boolean crawling) {
        return new BedrockClientPoseState(sneaking, crawling, swimming);
    }

    public BedrockClientPoseState withSwimming(boolean swimming) {
        return new BedrockClientPoseState(sneaking, crawling, swimming);
    }
}
