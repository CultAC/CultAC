package ac.cult.cultac.bedrock.prediction.world;

import java.util.Objects;

public record BubbleColumnLayer(int blockY, BubbleColumnLayerType type, boolean dragDown) {
    public BubbleColumnLayer {
        type = Objects.requireNonNull(type, "type");
    }

    public static BubbleColumnLayer inside(int blockY, boolean dragDown) {
        return new BubbleColumnLayer(blockY, BubbleColumnLayerType.INSIDE, dragDown);
    }

    public static BubbleColumnLayer above(int blockY, boolean dragDown) {
        return new BubbleColumnLayer(blockY, BubbleColumnLayerType.ABOVE, dragDown);
    }
}
