package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SocketLivenessTest {
    private static final long QUIET = 30;

    @Test
    void aSocketHeardFromRecentlyIsLeftAlone() {
        var liveness = new SocketLiveness(QUIET);
        liveness.opened(0);
        assertEquals(SocketLiveness.Action.NONE, liveness.check(QUIET));
    }

    @Test
    void aSocketThatAnsweredAPingAndThenGoesQuietIsDead() {
        var liveness = new SocketLiveness(QUIET);
        liveness.opened(0);
        assertEquals(SocketLiveness.Action.PING, liveness.check(QUIET + 1));
        liveness.pong(QUIET + 2);
        assertEquals(SocketLiveness.Action.NONE, liveness.check(2 * QUIET + 2));
        assertEquals(SocketLiveness.Action.PING, liveness.check(2 * QUIET + 3));
        assertEquals(SocketLiveness.Action.NONE, liveness.check(3 * QUIET + 3));
        assertEquals(SocketLiveness.Action.DEAD, liveness.check(3 * QUIET + 4));
    }

    @Test
    void aSilentSocketIsDroppedAfterThreeQuietPeriods() {
        var liveness = new SocketLiveness(QUIET);
        liveness.opened(0);
        assertEquals(SocketLiveness.Action.PING, liveness.check(QUIET + 1));
        assertEquals(SocketLiveness.Action.NONE, liveness.check(2 * QUIET + 1));
        assertEquals(SocketLiveness.Action.PING, liveness.check(2 * QUIET + 2));
        assertEquals(SocketLiveness.Action.NONE, liveness.check(3 * QUIET + 2));
        assertEquals(SocketLiveness.Action.DEAD, liveness.check(3 * QUIET + 3));
    }

    @Test
    void anythingHeardStartsTheNeverAnsweredCountAgain() {
        var liveness = new SocketLiveness(QUIET);
        liveness.opened(0);
        assertEquals(SocketLiveness.Action.PING, liveness.check(QUIET + 1));
        assertEquals(SocketLiveness.Action.PING, liveness.check(2 * QUIET + 2));
        liveness.heard(2 * QUIET + 5);
        assertEquals(SocketLiveness.Action.PING, liveness.check(3 * QUIET + 6));
        assertEquals(SocketLiveness.Action.PING, liveness.check(4 * QUIET + 7));
        assertEquals(SocketLiveness.Action.DEAD, liveness.check(5 * QUIET + 8));
    }

    @Test
    void aReplyAfterThePingKeepsTheSocket() {
        var liveness = new SocketLiveness(QUIET);
        liveness.opened(0);
        assertEquals(SocketLiveness.Action.PING, liveness.check(QUIET + 1));
        liveness.pong(QUIET + 5);
        assertEquals(SocketLiveness.Action.NONE, liveness.check(2 * QUIET + 2));
        assertEquals(SocketLiveness.Action.PING, liveness.check(2 * QUIET + 6));
        liveness.heard(2 * QUIET + 10);
        assertEquals(SocketLiveness.Action.NONE, liveness.check(3 * QUIET + 7));
    }

    @Test
    void aNewSocketGetsTheNeverAnsweredAllowanceAgain() {
        var liveness = new SocketLiveness(QUIET);
        liveness.opened(0);
        assertEquals(SocketLiveness.Action.PING, liveness.check(QUIET + 1));
        liveness.pong(QUIET + 2);
        liveness.opened(100);
        assertEquals(SocketLiveness.Action.PING, liveness.check(100 + QUIET + 1));
        assertEquals(SocketLiveness.Action.PING, liveness.check(100 + 2 * QUIET + 2));
        assertEquals(SocketLiveness.Action.DEAD, liveness.check(100 + 3 * QUIET + 3));
    }
}
