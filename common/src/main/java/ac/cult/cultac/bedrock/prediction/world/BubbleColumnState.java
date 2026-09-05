package ac.cult.cultac.bedrock.prediction.world;

import java.util.List;

public record BubbleColumnState(
    double minY,
    double maxY,
    double topAboveY,
    double topExitFeetY,
    double playerHeight,
    List<BubbleColumnLayer> layers
) {
    private static final double DEFAULT_PLAYER_HEIGHT = 1.8D;

    public static final BubbleColumnState NONE = new BubbleColumnState(
        0.0D,
        -1.0D,
        0.0D,
        0.0D,
        DEFAULT_PLAYER_HEIGHT
    );

    public BubbleColumnState(
        double minY,
        double maxY,
        double topAboveY,
        double topExitFeetY,
        double playerHeight
    ) {
        this(
            minY,
            maxY,
            topAboveY,
            topExitFeetY,
            playerHeight,
            List.of()
        );
    }

    public BubbleColumnState {
        if (playerHeight <= 0.0D) {
            throw new IllegalArgumentException("bubble column player height must be positive");
        }
        layers = List.copyOf(layers);
    }

    public boolean active() {
        return maxY >= minY;
    }
}
