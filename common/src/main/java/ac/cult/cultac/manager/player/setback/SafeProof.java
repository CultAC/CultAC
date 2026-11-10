package ac.cult.cultac.manager.player.setback;

/** How a safe position is known to be true, ordered by the transaction it is proven at. */
public record SafeProof(Kind kind, int transaction) {
    /** Neither sent nor proven, such as an observe-only release; it orders after nothing. */
    public static final SafeProof UNORDERED = new SafeProof(Kind.UNORDERED, Integer.MIN_VALUE);

    public enum Kind {
        /** The server or Cult sent this position after the ping {@code transaction}; the client may not have it yet. */
        PLACEMENT,
        /** The client answered the teleport sent after the ping {@code transaction}. */
        ANSWER,
        /** The client moved here after answering every ping up to {@code transaction}. */
        MOVEMENT,
        UNORDERED
    }

    public static SafeProof placement(int transaction) {
        return new SafeProof(Kind.PLACEMENT, transaction);
    }

    public static SafeProof answer(int transaction) {
        return new SafeProof(Kind.ANSWER, transaction);
    }

    public static SafeProof movement(int transaction) {
        return new SafeProof(Kind.MOVEMENT, transaction);
    }
}
