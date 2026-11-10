package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.List;
import java.util.Objects;

/**
 * The client state, besides a block's state and the moving actor, that its collision shape at a position reads:
 * the lid of a shulker box there, the block a piston carries through a moving block there, and the coordinate frame
 * that gives the block the position its offset hashes.
 */
public record BedrockBlockShapeContext(
        ShulkerBoxLift shulkerBoxLift, MovingBlockBoxes movingBlockBoxes, BedrockCoordinateFrame frame) {
    /** No client state: a closed lid, and a moving block that carries nothing yet, which is a cube. */
    public static final BedrockBlockShapeContext NONE = new BedrockBlockShapeContext(
            position -> 0.0F,
            position -> List.of(new WorldCollisionBox(
                    position.x(), position.y(), position.z(), position.x() + 1.0D, position.y() + 1.0D,
                    position.z() + 1.0D)),
            BedrockCoordinateFrame.IDENTITY);

    public BedrockBlockShapeContext {
        shulkerBoxLift = Objects.requireNonNull(shulkerBoxLift, "shulkerBoxLift");
        movingBlockBoxes = Objects.requireNonNull(movingBlockBoxes, "movingBlockBoxes");
        frame = Objects.requireNonNull(frame, "frame");
    }

    /** How far the lid of the shulker box at a position extends its collision box. */
    @FunctionalInterface
    public interface ShulkerBoxLift {
        float at(BlockPosition position);
    }

    /** The collision boxes of the moving block at a position, which may lie outside its cell. */
    @FunctionalInterface
    public interface MovingBlockBoxes {
        List<WorldCollisionBox> at(BlockPosition position);
    }
}
