package ac.cult.cultac.bedrock.prediction.state;

/**
 * Camera height and head-water history, tracked independently of collision dimensions.
 */
public record BedrockCameraWaterState(
    float currentOffset,
    float previousOffset,
    boolean headInWater,
    boolean headWasInWater
) {
    public static final float STANDING_EYE_HEIGHT = 1.62001F;
    public static final BedrockCameraWaterState INITIAL = new BedrockCameraWaterState(0.0F, 0.0F, false, false);

    public BedrockCameraWaterState {
        if (!Float.isFinite(currentOffset) || !Float.isFinite(previousOffset)) {
            throw new IllegalArgumentException("Camera offsets must be finite");
        }
    }

    /** Advances once per movement tick; packet tick gaps do not add updates. */
    public BedrockCameraWaterState advanceCamera(
        boolean horizontal, boolean sneaking, boolean sleeping,
        float basePositionOffset, float additionalOffset, float sneakHeightReduction
    ) {
        float eyeHeight = horizontal ? 0.4F
            : sneaking ? STANDING_EYE_HEIGHT - sneakHeightReduction
            : sleeping ? 0.2F : STANDING_EYE_HEIGHT;
        // Preserve float rounding at each operation; do not fold or snap the result.
        float change = (((basePositionOffset - eyeHeight) - additionalOffset) - currentOffset) * 0.5F;
        return new BedrockCameraWaterState(currentOffset + change, currentOffset, headInWater, headWasInWater);
    }

    /** Head sensing uses the previous camera offset, before interpolation. */
    public float sensingY(float actorPositionY, float previousRidingOffset, float attachmentOffsetY) {
        return ((previousRidingOffset + actorPositionY) - previousOffset) + attachmentOffsetY;
    }

    /** Updates water history on each sensing pass, without advancing the camera. */
    public BedrockCameraWaterState sense(boolean headSensingRequested, boolean belowWaterSurface) {
        return new BedrockCameraWaterState(currentOffset, previousOffset,
            headSensingRequested && belowWaterSurface, headInWater);
    }
}
