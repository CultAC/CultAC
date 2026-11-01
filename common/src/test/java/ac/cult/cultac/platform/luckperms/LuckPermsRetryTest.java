package ac.cult.cultac.platform.luckperms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LuckPermsRetryTest {
    private int attempts;
    private int upAfter = Integer.MAX_VALUE;
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private int cancelled;
    private Runnable atJoin;
    private final List<String> gaveUp = new ArrayList<>();

    private final LuckPermsRetry retry = new LuckPermsRetry(
            () -> ++attempts >= upAfter,
            (task, seconds) -> {
                delayed.add(task);
                delays.add(seconds);
                return () -> cancelled++;
            },
            task -> atJoin = task,
            gaveUp::add);

    private void runDelayed() {
        while (!delayed.isEmpty()) delayed.remove(0).run();
    }

    @Test
    void hooksAtOnceWhenTheApiIsUp() {
        upAfter = 1;
        retry.start();

        assertEquals(1, attempts);
        assertTrue(delayed.isEmpty());
        assertNull(atJoin);
    }

    @Test
    void retriesEveryFiveSecondsUntilTheApiComesUp() {
        upAfter = 4;
        retry.start();
        runDelayed();

        assertEquals(4, attempts);
        assertEquals(List.of(5L, 5L, 5L), delays);
        assertNull(atJoin);
        assertTrue(gaveUp.isEmpty());
    }

    @Test
    void afterSixtySecondsItTriesOnceMoreAtTheNextJoin() {
        retry.start();
        runDelayed();

        assertEquals(1 + LuckPermsRetry.RETRIES, attempts);
        assertEquals(60L, delays.stream().mapToLong(Long::longValue).sum());
        assertTrue(gaveUp.isEmpty());

        upAfter = attempts + 1;
        atJoin.run();
        assertEquals(LuckPermsRetry.RETRIES + 2, attempts);
        assertTrue(gaveUp.isEmpty());
    }

    @Test
    void itGivesUpWithOneLineWhenTheJoinAttemptFails() {
        retry.start();
        runDelayed();
        atJoin.run();
        atJoin.run();

        assertEquals(LuckPermsRetry.RETRIES + 2, attempts);
        assertEquals(1, gaveUp.size());
    }

    @Test
    void stopCancelsThePendingRetryAndNothingRunsAfterIt() {
        retry.start();
        retry.stop();
        runDelayed();

        assertEquals(1, cancelled);
        assertEquals(1, attempts);
        assertNull(atJoin);
    }
}
