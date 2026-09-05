package ac.cult.cultac.bedrock.prediction.geometry;

public enum BlockCollisionShapeScope {
    /** No vanilla collision boxes are available for this block shape. */
    NONE,
    /** The shape is class-wide and state-independent. */
    CONSTANT,
    /** The shape is deterministic for each resolved block state. */
    BLOCK_STATE,
    /** The shape also depends on actor, world, or runtime context. */
    CONTEXT;

    public boolean isConstantForResolvedState() {
        return this == CONSTANT || this == BLOCK_STATE;
    }
}
