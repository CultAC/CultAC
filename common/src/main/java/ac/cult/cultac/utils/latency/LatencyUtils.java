package ac.cult.cultac.utils.latency;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.netty.channel.ChannelHelper;

public class LatencyUtils {
    private final LatencyTaskQueue transactionMap = new LatencyTaskQueue();
    private final CultPlayer player;

    public LatencyUtils(CultPlayer player) {
        this.player = player;
    }

    // run when not on the player's netty thread
    public void addRealTimeTaskNowAsync(Runnable task) {
        addRealTimeTask(player.lastTransactionSent.get(), true, task);
    }

    public void addRealTimeTaskNextAsync(Runnable task) {
        addRealTimeTask(player.lastTransactionSent.get() + 1, true, task);
    }

    public void addRealTimeTaskNow(Runnable task) {
        addRealTimeTask(player.lastTransactionSent.get(), task);
    }

    public void addRealTimeTaskNext(Runnable task) {
        addRealTimeTask(player.lastTransactionSent.get() + 1, task);
    }

    public void addRealTimeTask(int transaction, Runnable runnable) {
        addRealTimeTask(transaction, false, runnable);
    }

    private Thread nettyThread = null;

    public boolean isThreadDebugEnabled() { return this.nettyThread != null; }

    public void disableThreadDebug() { this.nettyThread = null; }

    public void enableThreadDebug() { this.nettyThread = Thread.currentThread(); }

    private void assertNettyThread() {
        if (this.nettyThread != null && Thread.currentThread() != this.nettyThread) throw new RuntimeException("Task ran on incorrect thread!");
    }

    public void addRealTimeTask(int transaction, boolean async, Runnable task) {
        assertNettyThread();
        if (player.lastTransactionReceived.get() >= transaction) { // If the player already responded to this transaction
            if (async) {
                ChannelHelper.runInEventLoop(player.user.getChannel(), task);
            } else {
                task.run();
            }
            return;
        }
        transactionMap.add(transaction, task);
    }

    public void addRealTimeTaskWithNextTransaction(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
        assertNettyThread();
        if (player.lastTransactionReceived.get() >= transaction) { // If the player already responded to this transaction
            runnable.run();
            addRealTimeTask(transaction + 1, nextTransactionRunnable);
            return;
        }
        transactionMap.addWithNextTransaction(transaction, runnable, nextTransactionRunnable);
    }

    public void handleNettySyncTransaction(int transaction) {
        LatencyTaskQueue.TaskNode drained = transactionMap.drainReady(transaction);
        while (drained != null) {
            // Capture the link before clearing/running: the queued task may
            // enqueue further work and mutate node links.
            LatencyTaskQueue.TaskNode following = drained.next();
            Runnable queuedTask = drained.runnable();
            drained.clear();
            runQueuedTask(queuedTask);
            drained = following;
        }
    }

    public void runQueuedTask(Runnable queuedTask) {
        try {
            queuedTask.run();
        } catch (Exception e) {
            System.out.println("An error has occurred when running transactions for player: " + player.user.getName());
            e.printStackTrace();
        }
    }
}
