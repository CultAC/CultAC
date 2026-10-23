package ac.cult.reload.host;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ScopedResourcesTest {
    @Test
    void asynchronousCallDrainsEvenWhenItsCallerCancels() throws Exception {
        var scope = new ScopedResources();
        var engine = new CompletableFuture<String>();
        var result = scope.callAsync(() -> engine).toCompletableFuture();
        assertEquals(1, scope.inFlight());
        result.cancel(false);
        var closed = scope.close().toCompletableFuture();
        assertFalse(closed.isDone());
        assertThrows(RuntimeException.class, scope::acquire);
        engine.complete("done");
        closed.get(5, TimeUnit.SECONDS);
        assertEquals(0, scope.inFlight());
    }

    @Test
    void ticketsAndCancelledQueuedWorkReleaseExactlyOnce() throws Exception {
        var scope = new ScopedResources();
        var ticket = scope.acquire();
        var count = new AtomicInteger();
        Runnable queued = scope.once(count::incrementAndGet);
        var closed = scope.close().toCompletableFuture();
        ((AutoCloseable) queued).close();
        queued.run();
        ticket.close();
        ticket.close();
        closed.get(5, TimeUnit.SECONDS);
        assertEquals(0, count.get());
        assertEquals(0, scope.inFlight());
    }

    @Test
    void everyInvocationRestoresContextIncludingFailuresAndDrainingWork() throws Exception {
        var original = Thread.currentThread().getContextClassLoader();
        var owner = new ClassLoader(original) {};
        var scope = new ScopedResources(owner);
        assertThrows(
                IllegalArgumentException.class,
                () -> scope.call(() -> {
                    assertSame(owner, Thread.currentThread().getContextClassLoader());
                    throw new IllegalArgumentException("fixture");
                }));
        assertSame(original, Thread.currentThread().getContextClassLoader());
        Runnable queued =
                scope.once(() -> assertSame(owner, Thread.currentThread().getContextClassLoader()));
        var closed = scope.close().toCompletableFuture();
        queued.run();
        closed.get(5, TimeUnit.SECONDS);
        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void closeCancelsRecurringCallbacksAndWaitsForAcceptedWork() throws Exception {
        var scope = new ScopedResources();
        var executions = new AtomicInteger();
        Runnable recurring = scope.guard(executions::incrementAndGet);
        Runnable queued = scope.once(executions::incrementAndGet);
        recurring.run();
        var closed = scope.close().toCompletableFuture();
        assertFalse(closed.isDone());
        recurring.run();
        queued.run();
        closed.get(5, TimeUnit.SECONDS);
        assertEquals(2, executions.get());
        queued.run();
        assertEquals(2, executions.get());
    }

    @Test
    void failedCleanupNeverReportsSafeUnload() {
        var scope = new ScopedResources();
        scope.track(this, () -> {
            throw new IllegalStateException("Cannot detach native registration");
        });
        assertThrows(
                CompletionException.class,
                () -> scope.close().toCompletableFuture().join());
    }

    @Test
    void guardsNothingHoldsAreCollectedWhileTheScopeStaysOpen() throws Exception {
        var scope = new ScopedResources();
        var captured = new ArrayList<WeakReference<Object>>();
        for (int i = 0; i < 1000; i++) {
            Object player = new Object();
            captured.add(new WeakReference<>(player));
            Runnable task = scope.guard(() -> player.hashCode());
            // Half run, as a finished one-shot; the rest never do, as a cancelled one.
            if (i % 2 == 0) task.run();
        }
        awaitCollected(() -> scope.registrations() == 0
                && captured.stream().allMatch(reference -> reference.get() == null));
        assertEquals(0, scope.inFlight());
        scope.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void aGuardStillHeldIsDisarmedAtClose() throws Exception {
        var scope = new ScopedResources();
        var runs = new AtomicInteger();
        Runnable pending = scope.guard(runs::incrementAndGet);
        System.gc();
        scope.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pending.run();
        assertEquals(0, runs.get());
    }

    private static void awaitCollected(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "not collected");
            System.gc();
            Thread.sleep(10);
        }
    }
}
