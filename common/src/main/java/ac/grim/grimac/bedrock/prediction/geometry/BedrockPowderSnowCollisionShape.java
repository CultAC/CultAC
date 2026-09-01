package ac.grim.grimac.bedrock.prediction.geometry;

import java.util.List;
import java.util.Objects;

final class BedrockPowderSnowCollisionShape {
    private static final double FALL_INTO_MAX_Y = 0.899999976D;

    private BedrockPowderSnowCollisionShape() {
    }

    enum CollisionBranch {
        EMPTY,
        FALL_INTO_COLLISION,
        WALK_ON_TOP
    }

    static List<BlockAabb> collisionAabbs(CollisionBranch branch) {
        return switch (Objects.requireNonNull(branch, "branch")) {
            case EMPTY -> List.of();
            case FALL_INTO_COLLISION -> List.of(new BlockAabb(0.0D, 0.0D, 0.0D, 1.0D, FALL_INTO_MAX_Y, 1.0D));
            case WALK_ON_TOP -> List.of(new BlockAabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D));
        };
    }
}
