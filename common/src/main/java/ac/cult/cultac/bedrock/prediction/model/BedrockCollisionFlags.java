package ac.cult.cultac.bedrock.prediction.model;

public record BedrockCollisionFlags(
    boolean onGround,
    boolean horizontalCollision,
    boolean verticalCollision,
    boolean horizontalBlockContact,
    boolean liquidClimbOut,
    boolean verticalCollisionBelow,
    boolean xCollision,
    boolean zCollision
) {
    public static final BedrockCollisionFlags ON_GROUND = new BedrockCollisionFlags(true, false, true);
    public static final BedrockCollisionFlags VERTICAL_COLLISION = new BedrockCollisionFlags(false, false, true);
    public static final BedrockCollisionFlags AIR = new BedrockCollisionFlags(false, false, false);

    public BedrockCollisionFlags(boolean onGround, boolean horizontalCollision, boolean verticalCollision) {
        this(onGround, horizontalCollision, verticalCollision, horizontalCollision, false);
    }

    public BedrockCollisionFlags(
        boolean onGround,
        boolean horizontalCollision,
        boolean verticalCollision,
        boolean horizontalBlockContact,
        boolean liquidClimbOut
    ) {
        this(onGround, horizontalCollision, verticalCollision, horizontalBlockContact, liquidClimbOut, onGround && verticalCollision, horizontalCollision, horizontalCollision);
    }

    public BedrockCollisionFlags(
        boolean onGround,
        boolean horizontalCollision,
        boolean verticalCollision,
        boolean horizontalBlockContact,
        boolean liquidClimbOut,
        boolean xCollision,
        boolean zCollision
    ) {
        this(onGround, horizontalCollision, verticalCollision, horizontalBlockContact, liquidClimbOut, onGround && verticalCollision, xCollision, zCollision);
    }

    public BedrockCollisionFlags withHorizontalBlockContact(boolean nextHorizontalBlockContact) {
        if (horizontalBlockContact == nextHorizontalBlockContact) {
            return this;
        }
        return new BedrockCollisionFlags(
            onGround,
            horizontalCollision,
            verticalCollision,
            nextHorizontalBlockContact,
            liquidClimbOut,
            verticalCollisionBelow,
            xCollision,
            zCollision
        );
    }

    public BedrockCollisionFlags withTeleportOnGround(boolean nextOnGround) {
        return onGround == nextOnGround ? this : new BedrockCollisionFlags(
            nextOnGround, horizontalCollision, verticalCollision, horizontalBlockContact,
            liquidClimbOut, verticalCollisionBelow, xCollision, zCollision);
    }

    public BedrockCollisionFlags withOnGround(boolean nextOnGround) {
        if (onGround == nextOnGround) {
            return this;
        }
        return new BedrockCollisionFlags(
            nextOnGround,
            horizontalCollision,
            verticalCollision,
            horizontalBlockContact,
            liquidClimbOut,
            nextOnGround && verticalCollision || verticalCollisionBelow,
            xCollision,
            zCollision
        );
    }

    public BedrockCollisionFlags withVerticalCollision(boolean nextVerticalCollision) {
        if (verticalCollision == nextVerticalCollision) {
            return this;
        }
        return new BedrockCollisionFlags(
            onGround,
            horizontalCollision,
            nextVerticalCollision,
            horizontalBlockContact,
            liquidClimbOut,
            nextVerticalCollision && (verticalCollisionBelow || onGround),
            xCollision,
            zCollision
        );
    }

    public BedrockCollisionFlags withPacketCollision(
        boolean nextOnGround,
        boolean nextHorizontalCollision,
        boolean nextVerticalCollision
    ) {
        boolean nextXCollision = nextHorizontalCollision && xCollision;
        boolean nextZCollision = nextHorizontalCollision && zCollision;
        boolean nextVerticalCollisionBelow = nextVerticalCollision && (verticalCollisionBelow || nextOnGround);
        if (onGround == nextOnGround
            && horizontalCollision == nextHorizontalCollision
            && verticalCollision == nextVerticalCollision
            && horizontalBlockContact == nextHorizontalCollision
            && verticalCollisionBelow == nextVerticalCollisionBelow
            && xCollision == nextXCollision
            && zCollision == nextZCollision) {
            return this;
        }
        return new BedrockCollisionFlags(
            nextOnGround,
            nextHorizontalCollision,
            nextVerticalCollision,
            nextHorizontalCollision,
            liquidClimbOut,
            nextVerticalCollisionBelow,
            nextXCollision,
            nextZCollision
        );
    }

    public BedrockCollisionFlags withLiquidClimbOut(boolean nextLiquidClimbOut) {
        if (liquidClimbOut == nextLiquidClimbOut) {
            return this;
        }
        return new BedrockCollisionFlags(
            onGround,
            horizontalCollision,
            verticalCollision,
            horizontalBlockContact,
            nextLiquidClimbOut,
            verticalCollisionBelow,
            xCollision,
            zCollision
        );
    }
}
