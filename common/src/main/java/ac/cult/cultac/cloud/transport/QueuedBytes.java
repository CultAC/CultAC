package ac.cult.cultac.cloud.transport;

import java.util.concurrent.atomic.AtomicLong;

/** One socket's queued bytes; every change is also counted in the process total its transport holds. */
final class QueuedBytes {
    private final AtomicLong socket = new AtomicLong();
    private final AtomicLong process;

    QueuedBytes(AtomicLong process) {
        this.process = process;
    }

    long addAndGet(long delta) {
        process.addAndGet(delta);
        return socket.addAndGet(delta);
    }

    long get() {
        return socket.get();
    }

    long process() {
        return process.get();
    }

    /**
     * Adds {@code bytes} unless the socket would then hold more than {@code socketCap} or the process more than
     * {@code processCap}; false, and nothing added, when either would.
     */
    boolean tryAdd(long bytes, long socketCap, long processCap) {
        long processNow = process.addAndGet(bytes);
        long socketNow = socket.addAndGet(bytes);
        if (socketNow <= socketCap && processNow <= processCap) return true;
        socket.addAndGet(-bytes);
        process.addAndGet(-bytes);
        return false;
    }
}
