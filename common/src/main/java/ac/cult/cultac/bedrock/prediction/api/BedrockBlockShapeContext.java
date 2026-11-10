package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.Objects;

/**
 * The client state, besides a block's state and the moving actor, that its collision shape at a position reads:
 * the lid of a shulker box there, and the coordinate frame that gives the block the position its offset hashes.
 */
public record BedrockBlockShapeContext(ShulkerBoxLift shulkerBoxLift, BedrockCoordinateFrame frame) {
    public static final BedrockBlockShapeContext NONE =
            new BedrockBlockShapeContext(position -> 0.0F, BedrockCoordinateFrame.IDENTITY);

    public BedrockBlockShapeContext {
        shulkerBoxLift = Objects.requireNonNull(shulkerBoxLift, "shulkerBoxLift");
        frame = Objects.requireNonNull(frame, "frame");
    }

    /** How far the lid of the shulker box at a position extends its collision box. */
    @FunctionalInterface
    public interface ShulkerBoxLift {
        float at(BlockPosition position);
    }
}
