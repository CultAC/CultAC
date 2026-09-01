package ac.grim.grimac.bedrock.prediction.world;

import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import java.util.Objects;
import java.util.Optional;

public record BlockCollision(Optional<PlacedBlockCollision> block, WorldCollisionBox box) {
    public BlockCollision(PlacedBlockCollision block, WorldCollisionBox box) {
        this(Optional.of(block), box);
    }

    public BlockCollision {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(box, "box");
    }

    public static BlockCollision raw(WorldCollisionBox box) {
        return new BlockCollision(Optional.empty(), box);
    }
}
