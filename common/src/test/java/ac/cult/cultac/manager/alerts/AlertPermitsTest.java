package ac.cult.cultac.manager.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class AlertPermitsTest {
    private long now = 1_000;
    private final AlertPermits permits = new AlertPermits(() -> now);
    private final PlatformPlayer staffer = player();
    private final AtomicInteger asked = new AtomicInteger();
    private boolean holds = true;
    private final Predicate<PlatformPlayer> check = player -> {
        asked.incrementAndGet();
        return holds;
    };

    private static PlatformPlayer player() {
        PlatformPlayer player = mock(PlatformPlayer.class);
        when(player.getNative()).thenReturn(new Object());
        return player;
    }

    @Test
    void aYesIsTrustedUntilTheRecheckIsDue() {
        assertTrue(permits.permitted(AlertKind.ALERT, staffer, check));
        holds = false;
        now += AlertPermits.RECHECK_MILLIS - 1;
        assertTrue(permits.permitted(AlertKind.ALERT, staffer, check));
        assertEquals(1, asked.get());

        now += 1;
        assertFalse(permits.permitted(AlertKind.ALERT, staffer, check));
        assertEquals(2, asked.get());
    }

    @Test
    void aNoIsAskedAgainEveryTime() {
        holds = false;
        assertFalse(permits.permitted(AlertKind.ALERT, staffer, check));
        holds = true;
        assertTrue(permits.permitted(AlertKind.ALERT, staffer, check));
        assertEquals(2, asked.get());
    }

    @Test
    void eachKindAndPlayerIsAskedApart() {
        permits.permitted(AlertKind.ALERT, staffer, check);
        permits.permitted(AlertKind.VERBOSE, staffer, check);
        permits.permitted(AlertKind.ALERT, player(), check);
        assertEquals(3, asked.get());
    }

    @Test
    void aPlayerWithoutANativeHandleIsAskedEveryTimeAndForgottenQuietly() {
        PlatformPlayer detached = mock(PlatformPlayer.class);
        permits.permitted(AlertKind.ALERT, detached, check);
        permits.permitted(AlertKind.ALERT, detached, check);
        permits.forget(detached);
        assertEquals(2, asked.get());
    }

    @Test
    void aForgottenPlayerIsAskedAgain() {
        permits.permitted(AlertKind.ALERT, staffer, check);
        permits.forget(staffer);
        permits.permitted(AlertKind.ALERT, staffer, check);
        assertEquals(2, asked.get());
    }
}
