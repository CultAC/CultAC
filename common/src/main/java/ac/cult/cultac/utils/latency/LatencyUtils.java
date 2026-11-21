package ac.cult.cultac.utils.latency;

import ac.cult.cultac.network.packet.EntityMetadata;
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

    /**
     * Applies {@code packet}'s entity data to the compensated entities, proven at {@code transaction}, as
     * {@link #addRealTimeTask} runs a task.
     */
    public void addEntityDataTask(int transaction, EntityMetadata packet) {
        if (player.lastTransactionReceived.get() >= transaction) {
            applyEntityData(packet, transaction);
            return;
        }
        transactionMap.addPacket(transaction, packet);
    }

    private void applyEntityData(EntityMetadata packet, int transaction) {
        player.compensatedEntities.updateEntityMetadata(packet.id(), packet.packedItems(), transaction);
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
        LatencyTaskQueue.Batch drained = transactionMap.drainReady(transaction);
        if (drained == null) return;
        try {
            for (int i = 0; i < drained.size(); i++) runQueuedTask(drained.take(i), drained.transaction(i));
        } finally {
            transactionMap.release(drained);
        }
    }

    private void runQueuedTask(Object queuedTask, int transaction) {
        try {
            if (queuedTask instanceof EntityMetadata packet) applyEntityData(packet, transaction);
            else ((Runnable) queuedTask).run();
        } catch (Exception e) {
            FAILURES.error(e.getClass().getName(), "Transaction task failed for " + player.user.getName(), e);
        }
    }

    public void runQueuedTask(Runnable queuedTask) {
        try {
            queuedTask.run();
        } catch (Exception e) {
            FAILURES.error(e.getClass().getName(), "Transaction task failed for " + player.user.getName(), e);
        }
    }
}
