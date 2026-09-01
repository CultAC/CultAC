package ac.grim.grimac.bedrock.prediction.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BedrockPistonArmCollisionShape {
    private static final BlockAabb[][] COLLISION_TABLE = {
        {
            new BlockAabb(0.3125D, 0.75D, 0.3125D, 0.6875D, 1.25D, 0.6875D),
            new BlockAabb(0.375D, 0.25D, 0.375D, 0.625D, 0.75D, 0.625D),
            new BlockAabb(0.0D, 0.0D, 0.0D, 1.0D, 0.25D, 1.0D)
        },
        {
            new BlockAabb(0.3125D, -0.25D, 0.3125D, 0.6875D, 0.25D, 0.6875D),
            new BlockAabb(0.375D, 0.25D, 0.375D, 0.625D, 0.75D, 0.625D),
            new BlockAabb(0.0D, 0.75D, 0.0D, 1.0D, 1.0D, 1.0D)
        },
        {
            new BlockAabb(0.3125D, 0.3125D, -0.25D, 0.6875D, 0.6875D, 0.25D),
            new BlockAabb(0.375D, 0.375D, 0.25D, 0.625D, 0.625D, 0.75D),
            new BlockAabb(0.0D, 0.0D, 0.75D, 1.0D, 1.0D, 1.0D)
        },
        {
            new BlockAabb(0.3125D, 0.3125D, 0.75D, 0.6875D, 0.6875D, 1.25D),
            new BlockAabb(0.375D, 0.375D, 0.25D, 0.625D, 0.625D, 0.75D),
            new BlockAabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 0.25D)
        },
        {
            new BlockAabb(-0.25D, 0.3125D, 0.3125D, 0.25D, 0.6875D, 0.6875D),
            new BlockAabb(0.25D, 0.375D, 0.375D, 0.75D, 0.625D, 0.625D),
            new BlockAabb(0.75D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D)
        },
        {
            new BlockAabb(0.75D, 0.3125D, 0.3125D, 1.25D, 0.6875D, 0.6875D),
            new BlockAabb(0.25D, 0.375D, 0.375D, 0.75D, 0.625D, 0.625D),
            new BlockAabb(0.0D, 0.0D, 0.0D, 0.25D, 1.0D, 1.0D)
        }
    };

    private BedrockPistonArmCollisionShape() {
    }

    enum FacingDirection {
        DOWN(0, 0.0D, -1.0D, 0.0D),
        UP(1, 0.0D, 1.0D, 0.0D),
        POSITIVE_Z(2, 0.0D, 0.0D, 1.0D),
        NEGATIVE_Z(3, 0.0D, 0.0D, -1.0D),
        POSITIVE_X(4, 1.0D, 0.0D, 0.0D),
        NEGATIVE_X(5, -1.0D, 0.0D, 0.0D);

        private final int bedrockValue;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;

        FacingDirection(int bedrockValue, double offsetX, double offsetY, double offsetZ) {
            this.bedrockValue = bedrockValue;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }

        int bedrockValue() {
            return bedrockValue;
        }

        double offsetX() {
            return offsetX;
        }

        double offsetY() {
            return offsetY;
        }

        double offsetZ() {
            return offsetZ;
        }

        static FacingDirection fromBedrockValue(int bedrockValue) {
            for (FacingDirection direction : values()) {
                if (direction.bedrockValue == bedrockValue) {
                    return direction;
                }
            }
            throw new IllegalArgumentException("unknown Bedrock piston facing_direction " + bedrockValue);
        }
    }

    static List<BlockAabb> collisionAabbs(FacingDirection facingDirection, double progress) {
        FacingDirection direction = Objects.requireNonNull(facingDirection, "facingDirection");
        validateProgress(progress);
        List<BlockAabb> translated = new ArrayList<>(3);
        for (BlockAabb source : COLLISION_TABLE[direction.bedrockValue()]) {
            translated.add(translate(
                source,
                direction.offsetX() * progress,
                direction.offsetY() * progress,
                direction.offsetZ() * progress
            ));
        }
        return List.copyOf(translated);
    }

    private static BlockAabb translate(BlockAabb source, double x, double y, double z) {
        return new BlockAabb(
            source.minX() + x,
            source.minY() + y,
            source.minZ() + z,
            source.maxX() + x,
            source.maxY() + y,
            source.maxZ() + z
        );
    }

    private static void validateProgress(double progress) {
        if (!Double.isFinite(progress) || progress < 0.0D || progress > 1.0D) {
            throw new IllegalArgumentException("piston arm progress must be finite and in [0, 1]");
        }
    }
}
