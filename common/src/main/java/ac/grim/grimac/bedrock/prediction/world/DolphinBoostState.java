package ac.grim.grimac.bedrock.prediction.world;

public record DolphinBoostState(boolean boostAvailable) {
    public static final DolphinBoostState NONE = new DolphinBoostState(false);
}
