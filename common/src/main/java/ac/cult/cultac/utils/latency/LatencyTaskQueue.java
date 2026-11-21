package ac.cult.cultac.utils.latency;

import java.util.Arrays;

/**
 * Tasks waiting for a transaction, in one list in insertion order. A task waits for the current transaction or the
 * next one (an update outside a bundle is proven by the next ping), so no task is more than one below a task before
 * it, and a drain stops at the first task two past the answered transaction. A task's continuation takes its place at
 * the next transaction once it runs. A task added further below, such as a Bedrock receipt callback for an earlier
 * Java proof, goes before the first task two or more above it, which keeps that true.
 *
 * <p>The list is a ring of parallel arrays: slot {@code (head + i) & mask} holds the i-th task.
 */
final class LatencyTaskQueue {
    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_RETAINED_CAPACITY = 256;
    private static final Object TAKEN = new Object();

    private int[] transactions = new int[INITIAL_CAPACITY];
    /** A {@link Runnable}, or a packet queued with {@link #addPacket}. */
    private Object[] tasks = new Object[INITIAL_CAPACITY];
    /** Runs at the next transaction, in its task's place; null when there is none. */
    private Runnable[] continuations = new Runnable[INITIAL_CAPACITY];
    private int mask = INITIAL_CAPACITY - 1;
    private int head;
    private int size;
    /** The highest transaction added since the list was last empty. */
    private int highest = Integer.MIN_VALUE;
    private Batch spareBatch = new Batch();

    synchronized void add(int transaction, Runnable runnable) {
        insert(transaction, runnable, null);
    }

    /** Queues {@code packet} as a task that {@link LatencyUtils} runs by its type. */
    synchronized void addPacket(int transaction, Object packet) {
        insert(transaction, packet, null);
    }

    /**
     * Runs {@code runnable} at {@code transaction}, then {@code next} at the one after in {@code runnable}'s place:
     * right after it when both are ready, otherwise before the tasks added after it.
     */
    synchronized void addWithNextTransaction(int transaction, Runnable runnable, Runnable next) {
        insert(transaction, runnable, next);
    }

    private void insert(int transaction, Object task, Runnable continuation) {
        if (size == transactions.length) grow();
        if (size == 0 || transaction >= (long) highest - 1) {
            int slot = (head + size) & mask;
            transactions[slot] = transaction;
            tasks[slot] = task;
            continuations[slot] = continuation;
            size++;
            if (transaction > highest) highest = transaction;
            return;
        }
        int index = 0;
        while (index < size && transactions[(head + index) & mask] < (long) transaction + 2) index++;
        if (index < size - index) {
            head = (head - 1) & mask;
            for (int i = 0; i < index; i++) {
                int to = (head + i) & mask;
                int from = (head + i + 1) & mask;
                transactions[to] = transactions[from];
                tasks[to] = tasks[from];
                continuations[to] = continuations[from];
            }
        } else {
            for (int i = size; i > index; i--) {
                int to = (head + i) & mask;
                int from = (head + i - 1) & mask;
                transactions[to] = transactions[from];
                tasks[to] = tasks[from];
                continuations[to] = continuations[from];
            }
        }
        int slot = (head + index) & mask;
        transactions[slot] = transaction;
        tasks[slot] = task;
        continuations[slot] = continuation;
        size++;
    }

    /**
     * Takes every ready task in run order, each with the transaction it runs at; null when none is ready. The caller
     * owns the batch until it hands it to {@link #release}, so a drain while it runs takes another.
     */
    synchronized Batch drainReady(int transaction) {
        int[] transactions = this.transactions;
        Object[] tasks = this.tasks;
        Runnable[] continuations = this.continuations;
        int mask = this.mask;
        Batch batch = null;
        int walked = 0;
        int taken = 0;
        // No task behind one two past the answered transaction can be ready.
        while (walked < size) {
            int slot = (head + walked) & mask;
            int at = transactions[slot];
            if (at > (long) transaction + 1) break;
            walked++;
            if (at > transaction) continue;
            if (batch == null) batch = takeBatch();
            batch.append(tasks[slot], at);
            Runnable continuation = continuations[slot];
            continuations[slot] = null;
            int next = at + 1;
            if (continuation != null && next > transaction) {
                // Not ready yet: the continuation takes its task's place. Every task left before it is at its
                // transaction, so the list stays in order.
                transactions[slot] = next;
                tasks[slot] = continuation;
                if (next > highest) highest = next;
            } else {
                tasks[slot] = TAKEN;
                taken++;
                if (continuation != null) batch.append(continuation, next);
            }
        }
        if (taken > 0) {
            // The tasks left in the walked window close up toward its end, keeping their order.
            int write = walked;
            for (int read = walked - 1; read >= 0; read--) {
                int from = (head + read) & mask;
                if (tasks[from] == TAKEN) continue;
                int to = (head + --write) & mask;
                if (to != from) {
                    transactions[to] = transactions[from];
                    tasks[to] = tasks[from];
                    continuations[to] = continuations[from];
                }
            }
            for (int i = 0; i < taken; i++) {
                int slot = (head + i) & mask;
                tasks[slot] = null;
                continuations[slot] = null;
            }
            head = (head + taken) & mask;
            size -= taken;
            if (transactions.length > MAX_RETAINED_CAPACITY && size <= transactions.length >>> 2) {
                int capacity = Math.max(MAX_RETAINED_CAPACITY, Integer.highestOneBit(Math.max(1, size)) << 2);
                if (capacity < transactions.length) resize(capacity);
            }
        }
        if (size == 0) highest = Integer.MIN_VALUE;
        return batch;
    }

    private Batch takeBatch() {
        Batch batch = spareBatch;
        if (batch == null) return new Batch();
        spareBatch = null;
        return batch;
    }

    /** Returns a batch {@link #drainReady} gave once the caller has finished reading it. */
    synchronized void release(Batch batch) {
        if (batch == null || batch.tasks.length > MAX_RETAINED_CAPACITY) return;
        batch.clear();
        spareBatch = batch;
    }

    private void grow() {
        resize(transactions.length * 2);
    }

    private void resize(int capacity) {
        int[] newTransactions = new int[capacity];
        Object[] newTasks = new Object[capacity];
        Runnable[] newContinuations = new Runnable[capacity];
        for (int i = 0; i < size; i++) {
            int slot = (head + i) & mask;
            newTransactions[i] = transactions[slot];
            newTasks[i] = tasks[slot];
            newContinuations[i] = continuations[slot];
        }
        transactions = newTransactions;
        tasks = newTasks;
        continuations = newContinuations;
        mask = capacity - 1;
        head = 0;
    }

    /** Ready tasks in run order, each with the transaction it runs at. */
    static final class Batch {
        private Object[] tasks = new Object[INITIAL_CAPACITY];
        private int[] transactions = new int[INITIAL_CAPACITY];
        private int size;

        int size() {
            return size;
        }

        /** Hands out a {@link Runnable}, or a packet queued with {@link #addPacket}, and lets go of it. */
        Object take(int index) {
            Object task = tasks[index];
            tasks[index] = null;
            return task;
        }

        int transaction(int index) {
            return transactions[index];
        }

        private void append(Object task, int transaction) {
            if (size == tasks.length) {
                tasks = Arrays.copyOf(tasks, size * 2);
                transactions = Arrays.copyOf(transactions, size * 2);
            }
            tasks[size] = task;
            transactions[size] = transaction;
            size++;
        }

        private void clear() {
            Arrays.fill(tasks, 0, size, null);
            size = 0;
        }
    }
}
