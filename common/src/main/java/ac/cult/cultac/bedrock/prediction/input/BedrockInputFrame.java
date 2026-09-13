package ac.cult.cultac.bedrock.prediction.input;

import java.util.Set;

public record BedrockInputFrame(
    long clientTick,
    float yaw,
    float pitch,
    boolean jumping,
    boolean sneaking,
    boolean sprinting,
    Set<String> inputData,
    boolean swimmingRequested
) {
    public BedrockInputFrame {
        if (clientTick < 0L) {
            throw new IllegalArgumentException("clientTick must be non-negative");
        }
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
        inputData = inputData == null ? Set.of() : Set.copyOf(inputData);
    }

    public BedrockInputFrame(
        long clientTick,
        float yaw,
        float pitch,
        boolean jumping,
        boolean sneaking,
        boolean sprinting,
        Set<String> inputData
    ) {
        this(clientTick, yaw, pitch, jumping, sneaking, sprinting, inputData, false);
    }

    public BedrockInputFrame(
        long clientTick,
        float yaw,
        float pitch,
        boolean jumping,
        boolean sneaking,
        boolean sprinting
    ) {
        this(clientTick, yaw, pitch, jumping, sneaking, sprinting, Set.of());
    }

    public static BedrockInputFrame idle(long clientTick) {
        return new BedrockInputFrame(clientTick, 0.0F, 0.0F, false, false, false);
    }

    public BedrockInputIntent intent() {
        return BedrockInputIntent.from(this);
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
