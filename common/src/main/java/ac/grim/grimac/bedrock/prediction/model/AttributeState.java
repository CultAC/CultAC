package ac.grim.grimac.bedrock.prediction.model;

public record AttributeState(
    double baseMovementSpeed,
    double horizontalInputBaseMovementSpeed,
    float underwaterMovementSpeed,
    float lavaMovementSpeed,
    float jumpStrength,
    float frictionModifier
) {
    public static final double DEFAULT_BASE_MOVEMENT_SPEED = 0.10000000149011612D;
    public static final float DEFAULT_UNDERWATER_MOVEMENT_SPEED = 0.02F;
    public static final float DEFAULT_LAVA_MOVEMENT_SPEED = 0.02F;
    public static final float DEFAULT_JUMP_STRENGTH = 0.42F;
    public static final float DEFAULT_FRICTION_MODIFIER = 1.0F;
    public static final AttributeState DEFAULT = new AttributeState(
        DEFAULT_BASE_MOVEMENT_SPEED,
        DEFAULT_BASE_MOVEMENT_SPEED,
        DEFAULT_UNDERWATER_MOVEMENT_SPEED,
        DEFAULT_LAVA_MOVEMENT_SPEED,
        DEFAULT_JUMP_STRENGTH,
        DEFAULT_FRICTION_MODIFIER
    );

    public AttributeState(double baseMovementSpeed, float underwaterMovementSpeed) {
        this(
            baseMovementSpeed,
            baseMovementSpeed,
            underwaterMovementSpeed,
            DEFAULT_LAVA_MOVEMENT_SPEED,
            DEFAULT_JUMP_STRENGTH,
            DEFAULT_FRICTION_MODIFIER
        );
    }

    public AttributeState(double baseMovementSpeed, float underwaterMovementSpeed, float lavaMovementSpeed) {
        this(
            baseMovementSpeed,
            baseMovementSpeed,
            underwaterMovementSpeed,
            lavaMovementSpeed,
            DEFAULT_JUMP_STRENGTH,
            DEFAULT_FRICTION_MODIFIER
        );
    }

    public AttributeState(
        double baseMovementSpeed,
        float underwaterMovementSpeed,
        float lavaMovementSpeed,
        float jumpStrength
    ) {
        this(baseMovementSpeed, baseMovementSpeed, underwaterMovementSpeed, lavaMovementSpeed, jumpStrength, DEFAULT_FRICTION_MODIFIER);
    }

    public AttributeState(
        double baseMovementSpeed,
        double horizontalInputBaseMovementSpeed,
        float underwaterMovementSpeed,
        float lavaMovementSpeed,
        float jumpStrength
    ) {
        this(baseMovementSpeed, horizontalInputBaseMovementSpeed, underwaterMovementSpeed, lavaMovementSpeed, jumpStrength, DEFAULT_FRICTION_MODIFIER);
    }

    public AttributeState {
        if (!Double.isFinite(baseMovementSpeed) || baseMovementSpeed < 0.0D) {
            throw new IllegalArgumentException("base movement speed must be finite and non-negative");
        }
        if (!Double.isFinite(horizontalInputBaseMovementSpeed) || horizontalInputBaseMovementSpeed < 0.0D) {
            throw new IllegalArgumentException("horizontal input base movement speed must be finite and non-negative");
        }
        if (!Float.isFinite(underwaterMovementSpeed) || underwaterMovementSpeed < 0.0F) {
            throw new IllegalArgumentException("underwater movement speed must be finite and non-negative");
        }
        if (!Float.isFinite(lavaMovementSpeed) || lavaMovementSpeed < 0.0F) {
            throw new IllegalArgumentException("lava movement speed must be finite and non-negative");
        }
        if (!Float.isFinite(jumpStrength)) {
            throw new IllegalArgumentException("jump strength must be finite");
        }
        if (!Float.isFinite(frictionModifier) || frictionModifier < 0.0F) {
            throw new IllegalArgumentException("friction modifier must be finite and non-negative");
        }
    }
}
