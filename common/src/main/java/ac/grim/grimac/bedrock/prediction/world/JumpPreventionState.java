package ac.grim.grimac.bedrock.prediction.world;

import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import java.util.Objects;
import java.util.Optional;

public record JumpPreventionState(
    Optional<BlockPosition> blockingBlockPosition
) {
    public static final JumpPreventionState NONE = new JumpPreventionState(Optional.empty());

    public JumpPreventionState {
        blockingBlockPosition = Objects.requireNonNull(blockingBlockPosition, "blockingBlockPosition");
    }

    public static JumpPreventionState blockedBy(BlockPosition blockPosition) {
        return new JumpPreventionState(Optional.of(Objects.requireNonNull(blockPosition, "blockPosition")));
    }

    public boolean active() {
        return blockingBlockPosition.isPresent();
    }

}
