package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.List;
import java.util.Objects;

/**
 * The client state, besides a block's state and the moving actor, that its collision shape at a position reads:
 * the lid of a shulker box there, the block a piston carries through a moving block there, the progress of the
 * piston a piston arm belongs to, the coordinate frame that gives the block the position its offset hashes, and the
 * block a moving block carries, which a landing on it reads.
 */
public record BedrockBlockShapeContext(
        ShulkerBoxLift shulkerBoxLift,
        MovingBlockBoxes movingBlockBoxes,
        PistonProgress pistonProgress,
        BedrockCoordinateFrame frame,
        MovingBlockCarried movingBlockCarried) {
    /**
     * No client state: a closed lid, a moving block that carries nothing yet, which is a cube, and a piston that is
     * fully out.
     */
    public static final BedrockBlockShapeContext NONE = new BedrockBlockShapeContext(
            position -> 0.0F,
            position -> List.of(new WorldCollisionBox(
                    position.x(), position.y(), position.z(), position.x() + 1.0D, position.y() + 1.0D,
                    position.z() + 1.0D)),
            position -> 1.0F,
            BedrockCoordinateFrame.IDENTITY);

    public BedrockBlockShapeContext {
        shulkerBoxLift = Objects.requireNonNull(shulkerBoxLift, "shulkerBoxLift");
        movingBlockBoxes = Objects.requireNonNull(movingBlockBoxes, "movingBlockBoxes");
        pistonProgress = Objects.requireNonNull(pistonProgress, "pistonProgress");
        frame = Objects.requireNonNull(frame, "frame");
        movingBlockCarried = Objects.requireNonNull(movingBlockCarried, "movingBlockCarried");
    }

    /** A context whose moving blocks carry nothing a landing reads. */
    public BedrockBlockShapeContext(
            ShulkerBoxLift shulkerBoxLift,
            MovingBlockBoxes movingBlockBoxes,
            PistonProgress pistonProgress,
            BedrockCoordinateFrame frame) {
        this(shulkerBoxLift, movingBlockBoxes, pistonProgress, frame, position -> MovingBlockCarried.NONE);
    }

    /** How far the lid of the shulker box at a position extends its collision box. */
    @FunctionalInterface
    public interface ShulkerBoxLift {
        float at(BlockPosition position);
    }

    /** How far out, from 0 to 1, the piston at a position has pushed its arm; {@link #NO_PISTON} for no piston. */
    @FunctionalInterface
    public interface PistonProgress {
        float NO_PISTON = -1.0F;

        float at(BlockPosition position);
    }

    /** The collision boxes of the moving block at a position, which may lie outside its cell. */
    @FunctionalInterface
    public interface MovingBlockBoxes {
        List<WorldCollisionBox> at(BlockPosition position);
    }

    /** The block state the moving block at a position carries, or {@link #NONE} where there is no data for it. */
    @FunctionalInterface
    public interface MovingBlockCarried {
        int NONE = -1;

        int at(BlockPosition position);
    }
}
