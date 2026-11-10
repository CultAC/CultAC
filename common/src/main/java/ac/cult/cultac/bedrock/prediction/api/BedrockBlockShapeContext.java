package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import java.util.Objects;

/** The client state, besides a block's state and the moving actor, that its collision shape at a position reads. */
public record BedrockBlockShapeContext(ShulkerBoxLift shulkerBoxLift) {
    public static final BedrockBlockShapeContext NONE = new BedrockBlockShapeContext(position -> 0.0F);

    public BedrockBlockShapeContext {
        shulkerBoxLift = Objects.requireNonNull(shulkerBoxLift, "shulkerBoxLift");
    }

    /** How far the lid of the shulker box at a position extends its collision box. */
    @FunctionalInterface
    public interface ShulkerBoxLift {
        float at(BlockPosition position);
    }
}
