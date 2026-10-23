package ac.cult.reload.host;

import ac.cult.reload.api.ResourceScope;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One lifetime for calls, callbacks, accepted queued work, and registration cleanup. */
public final class ScopedResources implements ResourceScope {
    /** What close releases, newest first. */
    private final LinkedHashSet<Runnable> cleanup = new LinkedHashSet<>();
    /** Guards whose task was collected, so their entries can leave {@link #cleanup}. */
    private final ReferenceQueue<GuardedTask> collected = new ReferenceQueue<>();

    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean closing;
    private int inFlight;
    private Throwable failure;
    private volatile ClassLoader owner;

    public ScopedResources() {}

    public ScopedResources(ClassLoader owner) {
        this.owner = owner;
    }

    @Override
    public synchronized Lease acquire() {
        if (closing) throw RuntimeFailures.ended();
        return reserve();
    }

    private Lease reserve() {
        inFlight++;
        return new Ticket();
    }

    public synchronized int inFlight() {
        return inFlight;
    }

    public <T> T call(Callable<T> action) throws Exception {
        try (var ticket = acquire()) {
            return inContext(action);
        } catch (Throwable error) {
            throw RuntimeFailures.detached(error);
        }
    }

    public <T> CompletionStage<T> callAsync(Callable<? extends CompletionStage<T>> action) {
        Lease ticket;
        try {
            ticket = acquire();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(RuntimeFailures.detached(error));
        }
        var result = new CompletableFuture<T>();
        try {
            inContext(action).whenComplete((value, error) -> {
                ticket.close();
                if (error == null) result.complete(value);
                else result.completeExceptionally(RuntimeFailures.detached(error));
            });
        } catch (Throwable error) {
            ticket.close();
            result.completeExceptionally(RuntimeFailures.detached(error));
        }
        return result;
    }

    private <T> T inContext(Callable<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try {
            if (owner != null) thread.setContextClassLoader(owner);
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Override
    public <T> T track(T value, Runnable release) {
        if (register(release)) return value;
        release.run();
        throw RuntimeFailures.ended();
    }

    /**
     * The guard's entry holds it weakly. A native scheduler holding a pending or repeating task keeps it reachable, so
     * close still disarms it, while a task nothing holds any more, run or cancelled, is collected with its callback.
     */
    @Override
    public Runnable guard(Runnable callback) {
        var guarded = new GuardedTask(callback);
        if (register(new Disarm(guarded, collected))) return guarded;
        throw RuntimeFailures.ended();
    }

    private synchronized boolean register(Runnable release) {
        if (closing) return false;
        forgetCollected();
        cleanup.add(release);
        return true;
    }

    /** Entries close would release, after collected guards leave. */
    synchronized int registrations() {
        forgetCollected();
        return cleanup.size();
    }

    private void forgetCollected() {
        for (Reference<?> gone; (gone = collected.poll()) != null; ) cleanup.remove(gone);
    }

    @Override
    public void run(Runnable callback) {
        Lease ticket;
        synchronized (this) {
            if (closing) return;
            ticket = reserve();
        }
        runAccepted(ticket, callback);
    }

    private void runAccepted(Lease ticket, Runnable callback) {
        try (ticket) {
            inContext(() -> {
                callback.run();
                return null;
            });
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public Runnable once(Runnable callback) {
        return new OnceTask(acquire(), callback);
    }

    @Override
    public CompletionStage<Void> close() {
        List<Runnable> releases;
        Lease cancellation;
        synchronized (this) {
            if (closing) return stopped.minimalCompletionStage();
            closing = true;
            cancellation = reserve();
            releases = new ArrayList<>(cleanup.reversed());
            cleanup.clear();
        }
        try (cancellation) {
            for (var release : releases) {
                try {
                    release.run();
                } catch (Throwable error) {
                    synchronized (this) {
                        if (failure == null) failure = error;
                        else failure.addSuppressed(error);
                    }
                }
            }
        }
        return stopped.minimalCompletionStage();
    }

    private final class Ticket implements Lease {
        private boolean released;

        @Override
        public void close() {
            Throwable error;
            synchronized (ScopedResources.this) {
                if (released) return;
                released = true;
                inFlight--;
                if (!closing || inFlight != 0) return;
                error = failure;
                if (error == null) owner = null;
            }
            if (error == null) stopped.complete(null);
            else stopped.completeExceptionally(error);
        }
    }

    private final class GuardedTask implements Runnable {
        private volatile Runnable callback;

        GuardedTask(Runnable callback) {
            this.callback = callback;
        }

        public void run() {
            var action = callback;
            if (action != null) ScopedResources.this.run(action);
        }
    }

    /** A guard's entry: clears the task's callback at close if anything still holds the task. */
    private static final class Disarm extends WeakReference<GuardedTask> implements Runnable {
        Disarm(GuardedTask task, ReferenceQueue<GuardedTask> queue) {
            super(task, queue);
        }

        public void run() {
            var task = get();
            if (task != null) task.callback = null;
        }
    }

    private final class OnceTask implements Runnable, AutoCloseable {
        private final Lease ticket;
        private Runnable callback;

        OnceTask(Lease ticket, Runnable callback) {
            this.ticket = ticket;
            this.callback = callback;
        }

        public void run() {
            Runnable action;
            synchronized (this) {
                action = callback;
                callback = null;
            }
            if (action != null) runAccepted(ticket, action);
        }

        public void close() {
            synchronized (this) {
                if (callback == null) return;
                callback = null;
            }
            ticket.close();
        }
    }
}
