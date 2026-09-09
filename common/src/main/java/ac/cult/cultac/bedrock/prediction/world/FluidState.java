package ac.cult.cultac.bedrock.prediction.world;

import java.util.Objects;

public record FluidState(
    boolean current,
    boolean currentPositiveX,
    boolean currentNegativeX,
    boolean currentPositiveZ,
    boolean currentNegativeZ,
    boolean bubbleColumnUp,
    boolean bubbleColumnDown,
    FluidCurrentState currentState,
    BubbleColumnState bubbleColumnState,
    boolean waterWalkOnGroundComponentPresent,
    double swimSpeedMultiplier,
    boolean actorSwimming
) {
    public FluidState(
        boolean current,
        boolean currentPositiveX,
        boolean currentNegativeX,
        boolean currentPositiveZ,
        boolean currentNegativeZ,
        boolean bubbleColumnUp,
        boolean bubbleColumnDown,
        FluidCurrentState currentState,
        BubbleColumnState bubbleColumnState,
        boolean waterWalkOnGroundComponentPresent,
        double swimSpeedMultiplier
    ) {
        this(
            current,
            currentPositiveX,
            currentNegativeX,
            currentPositiveZ,
            currentNegativeZ,
            bubbleColumnUp,
            bubbleColumnDown,
            currentState,
            bubbleColumnState,
            waterWalkOnGroundComponentPresent,
            swimSpeedMultiplier,
            false
        );
    }

    public static final FluidState NONE = new FluidState(
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        FluidCurrentState.NONE,
        BubbleColumnState.NONE,
        false,
        1.0D
    );

    public FluidState withSwimSpeedMultiplier(double value) {
        if (swimSpeedMultiplier == value) {
            return this;
        }
        return new FluidState(current, currentPositiveX, currentNegativeX, currentPositiveZ,
            currentNegativeZ, bubbleColumnUp, bubbleColumnDown, currentState, bubbleColumnState,
            waterWalkOnGroundComponentPresent, value, actorSwimming);
    }

    public FluidState {
        currentState = Objects.requireNonNull(currentState, "currentState");
        bubbleColumnState = Objects.requireNonNull(bubbleColumnState, "bubbleColumnState");
        if (!Double.isFinite(swimSpeedMultiplier) || swimSpeedMultiplier < 0.0D) {
            throw new IllegalArgumentException("swimSpeedMultiplier must be finite and non-negative");
        }
    }
}
