package ac.cult.cultac.bedrock.prediction.world;

import java.util.List;

/** The bubble column blocks the actor's box touches, one layer per block. */
public record BubbleColumnState(List<BubbleColumnLayer> layers) {
    public static final BubbleColumnState NONE = new BubbleColumnState(List.of());

    public BubbleColumnState {
        layers = List.copyOf(layers);
    }
}
