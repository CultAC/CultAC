package ac.cult.cultac.bedrock.prediction.world;

import java.util.Objects;

public record BubbleColumnLayer(
    int blockY,
    BubbleColumnLayerType type,
    boolean dragDown,
    long activeFromTick,
    long activeUntilTick
) {
    public BubbleColumnLayer {
        type = Objects.requireNonNull(type, "type");
        if (activeFromTick < 0L) {
            throw new IllegalArgumentException("bubble column layer activation tick must be non-negative");
        }
        if (activeUntilTick <= activeFromTick) {
            throw new IllegalArgumentException("bubble column layer active-until tick must follow activation tick");
        }
    }

    public static BubbleColumnLayer inside(int blockY, long activeFromTick) {
        return inside(blockY, activeFromTick, Long.MAX_VALUE);
    }

    public static BubbleColumnLayer inside(int blockY, long activeFromTick, long activeUntilTick) {
        return inside(blockY, false, activeFromTick, activeUntilTick);
    }

    public static BubbleColumnLayer inside(int blockY, boolean dragDown, long activeFromTick, long activeUntilTick) {
        return new BubbleColumnLayer(blockY, BubbleColumnLayerType.INSIDE, dragDown, activeFromTick, activeUntilTick);
    }

    public static BubbleColumnLayer above(int blockY, long activeFromTick, long activeUntilTick) {
        return above(blockY, false, activeFromTick, activeUntilTick);
    }

    public static BubbleColumnLayer above(int blockY, boolean dragDown, long activeFromTick, long activeUntilTick) {
        return new BubbleColumnLayer(blockY, BubbleColumnLayerType.ABOVE, dragDown, activeFromTick, activeUntilTick);
    }

    public boolean activeAt(long simulationTick) {
        return activeFromTick <= simulationTick && simulationTick < activeUntilTick;
    }
}
