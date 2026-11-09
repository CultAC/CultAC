package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.Hand;
import java.util.ArrayDeque;

/**
 * Swings the client sends from a packet handler rather than its own tick. Before 26.3,
 * ClientPacketListener#handleAnimate swings the local player for action 0 (main hand) and 3 (off hand), and
 * LocalPlayer#swing sends ServerboundSwingPacket while the client handles the animate.
 */
public final class ServerSwingEcho {
    private final ArrayDeque<Animate> mainHand = new ArrayDeque<>();
    private final ArrayDeque<Animate> offHand = new ArrayDeque<>();
    private boolean echo;

    private record Animate(int lead, int response) {}

    /** The server sent a self animate swinging {@code hand}, between the pings {@code lead} and {@code response}. */
    public void expect(Hand hand, int lead, int response) {
        pending(hand).addLast(new Animate(lead, response));
    }

    /** The client has answered {@code response}, so it has handled the animate. */
    public void expire(Hand hand, int response) {
        pending(hand).removeIf(animate -> animate.response() == response);
    }

    /** Classifies a swing for {@code hand} received after the client answered {@code received}. */
    public void classify(Hand hand, int received) {
        ArrayDeque<Animate> pending = pending(hand);
        Animate next = pending.peekFirst();
        echo = next != null && received >= next.lead();
        if (echo) pending.pollFirst();
    }

    /** Whether the swing last classified is the one the client sent while handling a self animate. */
    public boolean isEcho() {
        return echo;
    }

    private ArrayDeque<Animate> pending(Hand hand) {
        return hand == Hand.OFF_HAND ? offHand : mainHand;
    }
}
