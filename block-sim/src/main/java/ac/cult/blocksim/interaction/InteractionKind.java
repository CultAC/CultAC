package ac.cult.blocksim.interaction;

public enum InteractionKind {
    SUCCESS, CONSUME, FAIL, PASS, TRY_WITH_EMPTY_HAND;

    public boolean consumesAction() { return this == SUCCESS || this == CONSUME; }
}
