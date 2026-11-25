package ac.cult.cultac.bedrock.prediction.world;

/**
 * The mobs a riptide spin meets at the start of a tick: each one whose box meets the player's box grown by a block on
 * every side. The spin rebounds off {@code hits} of them. Cult's box for a moving mob can stand off the client's, so
 * the client may meet any count from {@code hits} up to {@code possible}.
 */
public record SpinContact(int hits, int possible) {
    public static final SpinContact NONE = new SpinContact(0, 0);

    public SpinContact {
        if (hits < 0 || possible < hits) {
            throw new IllegalArgumentException("spin contact needs 0 <= hits <= possible");
        }
    }

    /** The same contact with the spin rebounding off {@code hits} of its mobs. */
    public SpinContact withHits(int hits) {
        return hits == this.hits ? this : new SpinContact(hits, Math.max(hits, possible));
    }
}
