package ac.cult.cultac.bedrock.prediction.world;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import java.util.List;

public record HoneySlideState(
    boolean active,
    double actorWidth,
    List<BlockPosition> honeyBlockPositions
) {
    public static final double DEFAULT_PLAYER_WIDTH = 0.6D;
    public static final HoneySlideState NONE =
        new HoneySlideState(false, DEFAULT_PLAYER_WIDTH, List.of());

    public HoneySlideState {
        if (!Double.isFinite(actorWidth) || actorWidth < 0.0D) {
            throw new IllegalArgumentException("honey slide actor width must be finite and non-negative");
        }
        honeyBlockPositions = List.copyOf(honeyBlockPositions);
        if (!active && !honeyBlockPositions.isEmpty()) {
            throw new IllegalArgumentException("inactive honey slide state must not carry block positions");
        }
    }

    public static HoneySlideState active(double actorWidth, List<BlockPosition> honeyBlockPositions) {
        return new HoneySlideState(true, actorWidth, honeyBlockPositions);
    }

    public static HoneySlideState player(List<BlockPosition> honeyBlockPositions) {
        return active(DEFAULT_PLAYER_WIDTH, honeyBlockPositions);
    }
}
