package ac.cult.cultac.cloud.capture;

/**
 * One serverbound packet of the stream, as an outcome names it: the seq of the frame it was cut into and its index
 * among that frame's serverbound records as they went out. Only touched on the player's event loop.
 */
public final class ActionRef {
    static final int OPEN = 0;
    /** Cut into a frame: {@link #frameSeq} is set. */
    static final int CUT = 1;
    /** The frame went out: {@link #index} is set, or -1 when this packet was left out of it. */
    static final int SENT = 2;
    /** The frame never went out. */
    static final int LOST = 3;

    /** The capture the packet was tapped under; an outcome for another capture's packet is dropped. */
    final long generation;
    /** The packet's position among the open frame's records; -1 when the frame does not hold it. */
    int position;

    int state = OPEN;
    long frameSeq = -1;
    int index = -1;

    ActionRef(long generation, int position) {
        this.generation = generation;
        this.position = position;
    }

    boolean settled() {
        return state == SENT || state == LOST;
    }
}
