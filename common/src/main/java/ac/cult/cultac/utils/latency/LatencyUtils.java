package ac.cult.cultac.utils.latency;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogThrottle;

public class LatencyUtils {
    private static final LogThrottle FAILURES = new LogThrottle();
    private final LatencyTaskQueue transactionMap = new LatencyTaskQueue();
    private final CultPlayer player;

    public LatencyUtils(CultPlayer player) {
        this.player = player;
    }

    public void addRealTimeTaskNow(Runnable task) {
        addRealTimeTask(player.lastTransactionSent.get(), task);
    }

    public void addRealTimeTaskNext(Runnable task) {
        addRealTimeTask(player.lastTransactionSent.get() + 1, task);
    }

    public void addRealTimeTask(int transaction, Runnable task) {
        if (player.lastTransactionReceived.get()
                >= transaction) { // If the player already responded to this transaction
            task.run();
            return;
        }
        transactionMap.add(transaction, task);
    }

    public void addRealTimeTaskWithNextTransaction(
            int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
        if (player.lastTransactionReceived.get()
                >= transaction) { // If the player already responded to this transaction
            runnable.run();
            addRealTimeTask(transaction + 1, nextTransactionRunnable);
            return;
        }
        transactionMap.addWithNextTransaction(transaction, runnable, nextTransactionRunnable);
    }

    public void handleNettySyncTransaction(int transaction) {
        LatencyTaskQueue.TaskNode drained = transactionMap.drainReady(transaction);
        LatencyTaskQueue.TaskNode released = null;
        while (drained != null) {
            // Capture the link before clearing/running: the queued task may
            // enqueue further work and mutate node links.
            LatencyTaskQueue.TaskNode following = drained.next();
            Runnable queuedTask = drained.runnable();
            released = drained.clearAndLink(released);
            runQueuedTask(queuedTask);
            drained = following;
        }
        transactionMap.recycle(released);
    }

    public void runQueuedTask(Runnable queuedTask) {
        try {
            queuedTask.run();
        } catch (Exception e) {
            FAILURES.error(e.getClass().getName(), "Transaction task failed for " + player.user.getName(), e);
        }
    }
}
