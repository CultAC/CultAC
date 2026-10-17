package ac.cult.cultac.cloud;

import ac.cult.cultac.player.CultPlayer;
import java.util.function.Consumer;

/**
 * Names the backend each of a player's capture segments belongs to. A segment runs from a clientbound play
 * {@code login} to the next clientbound {@code start_configuration} or the disconnect, and is uploaded on the socket of
 * the backend it is bound to. A proxy binds from its connection events; a server binds every segment to itself.
 */
public interface SegmentBinder {
    /** Binds no segment. */
    SegmentBinder NONE = (player, bind, playing) -> {};

    /**
     * {@code player}'s new segment started, unbound, at a play {@code login} sent on its event loop, or mid-play at an
     * install when {@code playing}, so the player's current backend can name it at once. The binder calls
     * {@code bind} at most once, from any thread, with the backend's name; a segment still unbound 5 s after it started
     * is discarded through {@link #abandoned}.
     */
    void started(CultPlayer player, Consumer<String> bind, boolean playing);

    /** {@code player}'s segment was discarded unbound; a pending bind is dropped. */
    default void abandoned(CultPlayer player) {}

    /**
     * {@code player}'s segment ended at a {@code start_configuration} sent on its event loop, before the next
     * {@link #started}; a proxy records which backend connection it ended on.
     */
    default void configuring(CultPlayer player) {}

    /** {@code player} disconnected; a pending bind is dropped. */
    default void disconnected(CultPlayer player) {}

    /** Whether {@code player}'s backend answers {@link #mark}. On the player's event loop. */
    default boolean marks(CultPlayer player) {
        return false;
    }

    /**
     * Sends {@code marker} to {@code player}'s backend behind the packets already forwarded to it; the backend's answer
     * goes to {@link CloudCapture#markerAnswered} after every update the server sent the player for them. On the
     * player's event loop.
     */
    default void mark(CultPlayer player, int marker) {}
}
