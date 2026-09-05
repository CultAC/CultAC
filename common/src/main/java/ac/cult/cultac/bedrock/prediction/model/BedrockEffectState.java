package ac.cult.cultac.bedrock.prediction.model;

import java.util.Objects;

public record BedrockEffectState(
    int slownessLevel,
    int speedLevel,
    int jumpBoostLevel,
    int levitationLevel,
    boolean blindness,
    boolean slowFalling,
    boolean weaving,
    BedrockEffectState.MovementSpeedEffectOrder movementSpeedEffectOrder
) {
    public static final BedrockEffectState NONE = new BedrockEffectState(0, 0, 0, 0, false, false, false);
    private static final double SLOWNESS_SPEED_REDUCTION_PER_LEVEL = 0.15D;
    private static final double SPEED_SPEED_BONUS_PER_LEVEL = 0.2D;

    public BedrockEffectState(
        int slownessLevel,
        int speedLevel,
        int jumpBoostLevel,
        int levitationLevel,
        boolean blindness,
        boolean slowFalling
    ) {
        this(slownessLevel, speedLevel, jumpBoostLevel, levitationLevel, blindness, slowFalling, false);
    }

    public BedrockEffectState(
        int slownessLevel,
        int speedLevel,
        int jumpBoostLevel,
        int levitationLevel,
        boolean blindness,
        boolean slowFalling,
        boolean weaving
    ) {
        this(
            slownessLevel,
            speedLevel,
            jumpBoostLevel,
            levitationLevel,
            blindness,
            slowFalling,
            weaving,
            MovementSpeedEffectOrder.SLOWNESS_THEN_SPEED
        );
    }

    public BedrockEffectState {
        movementSpeedEffectOrder = Objects.requireNonNull(movementSpeedEffectOrder, "movementSpeedEffectOrder");
        if (slownessLevel < 0) {
            throw new IllegalArgumentException("slownessLevel must be non-negative");
        }
        if (speedLevel < 0) {
            throw new IllegalArgumentException("speedLevel must be non-negative");
        }
        if (jumpBoostLevel < 0) {
            throw new IllegalArgumentException("jumpBoostLevel must be non-negative");
        }
        if (levitationLevel < 0) {
            throw new IllegalArgumentException("levitationLevel must be non-negative");
        }
    }

    public BedrockEffectState withSlownessLevel(int value) {
        return new BedrockEffectState(value, speedLevel, jumpBoostLevel, levitationLevel, blindness, slowFalling, weaving, movementSpeedEffectOrder);
    }

    public BedrockEffectState withSpeedLevel(int value) {
        return new BedrockEffectState(slownessLevel, value, jumpBoostLevel, levitationLevel, blindness, slowFalling, weaving, movementSpeedEffectOrder);
    }

    public BedrockEffectState withJumpBoostLevel(int value) {
        return new BedrockEffectState(slownessLevel, speedLevel, value, levitationLevel, blindness, slowFalling, weaving, movementSpeedEffectOrder);
    }

    public BedrockEffectState withLevitationLevel(int value) {
        return new BedrockEffectState(slownessLevel, speedLevel, jumpBoostLevel, value, blindness, slowFalling, weaving, movementSpeedEffectOrder);
    }

    public BedrockEffectState withSlowFalling(boolean value) {
        return new BedrockEffectState(slownessLevel, speedLevel, jumpBoostLevel, levitationLevel, blindness, value, weaving, movementSpeedEffectOrder);
    }

    public BedrockEffectState withWeaving(boolean value) {
        return new BedrockEffectState(slownessLevel, speedLevel, jumpBoostLevel, levitationLevel, blindness, slowFalling, value, movementSpeedEffectOrder);
    }

    public float applyMovementSpeedEffects(float speed) {
        return switch (movementSpeedEffectOrder) {
            case SLOWNESS_THEN_SPEED -> applySpeedEffect(applySlownessEffect(speed));
            case SPEED_THEN_SLOWNESS -> applySlownessEffect(applySpeedEffect(speed));
        };
    }

    private float applySlownessEffect(float speed) {
        if (slownessLevel <= 0) {
            return speed;
        }
        return Math.max(0.0F, speed * (float) (1.0D - SLOWNESS_SPEED_REDUCTION_PER_LEVEL * slownessLevel));
    }

    private float applySpeedEffect(float speed) {
        if (speedLevel <= 0) {
            return speed;
        }
        return speed * (float) (1.0D + SPEED_SPEED_BONUS_PER_LEVEL * speedLevel);
    }

    public enum MovementSpeedEffectOrder {
        SLOWNESS_THEN_SPEED,
        SPEED_THEN_SLOWNESS
    }
}
