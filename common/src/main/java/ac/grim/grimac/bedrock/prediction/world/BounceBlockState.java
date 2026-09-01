package ac.grim.grimac.bedrock.prediction.world;

import java.util.Objects;
import java.util.Optional;

public record BounceBlockState(
    BounceBlockType type,
    double surfaceY
) {
    private static final String SLIME_BLOCK_IDENTIFIER = "minecraft:slime";
    private static final String JAVA_SLIME_BLOCK_IDENTIFIER = "minecraft:slime_block";
    private static final String BED_BLOCK_IDENTIFIER = "minecraft:bed";
    private static final float BED_BOUNCE_SCALE = -0.75F;
    private static final float BED_BOUNCE_CAP = 0.75F;

    public BounceBlockState {
        type = Objects.requireNonNull(type, "type");
        if (!Double.isFinite(surfaceY)) {
            throw new IllegalArgumentException("bounce block surface Y must be finite");
        }
    }

    public static Optional<BounceBlockState> fromCollisionBlock(
        PlacedBlockCollision block,
        double surfaceY
    ) {
        Objects.requireNonNull(block, "block");
        BounceBlockType type = collisionBlockType(block);
        return type == BounceBlockType.NONE
            ? Optional.empty()
            : Optional.of(new BounceBlockState(type, surfaceY));
    }

    public double reboundVelocityY(double fallingVelocityY) {
        if (!Double.isFinite(fallingVelocityY)) {
            throw new IllegalArgumentException("bounce block falling velocity Y must be finite");
        }
        if (fallingVelocityY > 0.0D) {
            throw new IllegalArgumentException("bounce block falling velocity Y must be non-positive");
        }
        return switch (type) {
            case NONE -> 0.0D;
            case SLIME -> -(float) fallingVelocityY;
            case BED -> Math.min((float) fallingVelocityY * BED_BOUNCE_SCALE, BED_BOUNCE_CAP);
        };
    }

    private static BounceBlockType collisionBlockType(PlacedBlockCollision block) {
        if (isSlimeBlockIdentifier(block.bedrockIdentifier()) || isSlimeBlockIdentifier(block.javaState())) {
            return BounceBlockType.SLIME;
        }
        if (isBedBlockIdentifier(block.bedrockIdentifier()) || isBedBlockIdentifier(block.javaState())) {
            return BounceBlockType.BED;
        }
        return BounceBlockType.NONE;
    }

    private static boolean isSlimeBlockIdentifier(String identifier) {
        return SLIME_BLOCK_IDENTIFIER.equals(identifier)
            || JAVA_SLIME_BLOCK_IDENTIFIER.equals(identifier)
            || identifier.startsWith(SLIME_BLOCK_IDENTIFIER + "[")
            || identifier.startsWith(JAVA_SLIME_BLOCK_IDENTIFIER + "[");
    }

    private static boolean isBedBlockIdentifier(String identifier) {
        return BED_BLOCK_IDENTIFIER.equals(identifier)
            || identifier.startsWith(BED_BLOCK_IDENTIFIER + "[")
            || identifier.startsWith("minecraft:") && identifier.endsWith("_bed")
            || identifier.startsWith("minecraft:") && identifier.contains("_bed[");
    }
}
