package ac.grim.grimac.bedrock.prediction.simulation.frame;

public record BedrockClimbableState(
    boolean ascending,
    boolean holdingSneak,
    boolean horizontalControl
) {
    public static final BedrockClimbableState NONE = new BedrockClimbableState(false, false, false);
}
