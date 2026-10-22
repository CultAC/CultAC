package ac.cult.cultac.utils.latency;

final class LatencyTaskQueue {
    private static final int MAX_RECYCLED_TASKS = 256;
    private TaskNode firstTask;
    private TaskNode lastTask;
    private TaskNode recycledTasks;
    private int recycledTaskCount;
    // Set once a task is appended behind a later transaction. While clear, the
    // pending transactions never decrease front to back, so a drain can stop at
    // the first future task. Recomputed exactly by every full scan.
    private boolean outOfOrder;

    synchronized void add(int transaction, Runnable runnable) {
        add(acquire(transaction, runnable, null));
    }

    synchronized void addWithNextTransaction(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
        add(acquire(transaction, runnable, nextTransactionRunnable));
    }

    private TaskNode acquire(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
        TaskNode task = recycledTasks;
        if (task == null) return new TaskNode(transaction, runnable, nextTransactionRunnable);
        recycledTasks = task.next;
        recycledTaskCount--;
        task.transaction = transaction;
        task.runnable = runnable;
        task.nextTransactionRunnable = nextTransactionRunnable;
        task.next = null;
        return task;
    }

    // The caller must finish walking the drained batch before returning any of its nodes.
    synchronized void recycle(TaskNode task) {
        if (task == null || recycledTaskCount == MAX_RECYCLED_TASKS) return;
        TaskNode tail = task;
        int count = 1;
        while (recycledTaskCount + count < MAX_RECYCLED_TASKS && tail.next != null) {
            tail = tail.next;
            count++;
        }
        tail.next = recycledTasks;
        recycledTasks = task;
        recycledTaskCount += count;
    }

    private TaskNode nextTransactionTask(TaskNode task) {
        if (task.nextTransactionRunnable == null) return null;
        TaskNode continuation = acquire(task.transaction + 1, task.nextTransactionRunnable, null);
        task.nextTransactionRunnable = null;
        return continuation;
    }

    private void add(TaskNode task) {
        if (lastTask == null) {
            firstTask = task;
        } else {
            if (task.transaction < lastTask.transaction) {
                outOfOrder = true;
            }
            lastTask.next = task;
        }
        lastTask = task;
    }

    synchronized TaskNode drainReady(int transaction) {
        TaskNode previous = null;
        TaskNode current = firstTask;
        TaskNode readyFirst = null;
        TaskNode readyLast = null;
        // A monotone queue drains a prefix; every continuation left in place is
        // transaction + 1, which is <= every task behind the stop point, so the
        // queue stays monotone. A full scan instead recomputes monotonicity over
        // exactly the tasks it leaves queued.
        boolean fullScan = outOfOrder;
        boolean keptMonotone = true;
        boolean anyKept = false;
        int lastKept = 0;

        while (current != null) {
            TaskNode next = current.next;
            // Receipt callbacks can append earlier transactions behind later ones.
            // Keep scanning so future tasks cannot hide ready work.
            if (transaction < current.transaction) {
                if (!fullScan) break;
                if (anyKept && current.transaction < lastKept) keptMonotone = false;
                anyKept = true;
                lastKept = current.transaction;
                previous = current;
                current = next;
                continue;
            }

            TaskNode continuation = nextTransactionTask(current);
            if (continuation != null) {
                if (continuation.transaction <= transaction) {
                    if (previous == null) {
                        firstTask = next;
                    } else {
                        previous.next = next;
                    }
                    if (lastTask == current) {
                        lastTask = previous;
                    }

                    current.next = continuation;
                    if (readyFirst == null) {
                        readyFirst = current;
                    } else {
                        readyLast.next = current;
                    }
                    readyLast = continuation;
                    current = next;
                    continue;
                }

                continuation.next = next;
                if (previous == null) {
                    firstTask = continuation;
                } else {
                    previous.next = continuation;
                }
                if (lastTask == current) {
                    lastTask = continuation;
                }

                if (anyKept && continuation.transaction < lastKept) keptMonotone = false;
                anyKept = true;
                lastKept = continuation.transaction;
                current.next = null;
                if (readyFirst == null) {
                    readyFirst = current;
                } else {
                    readyLast.next = current;
                }
                readyLast = current;
                previous = continuation;
                current = next;
                continue;
            }

            if (previous == null) {
                firstTask = next;
            } else {
                previous.next = next;
            }
            if (lastTask == current) {
                lastTask = previous;
            }

            current.next = null;
            if (readyFirst == null) {
                readyFirst = current;
            } else {
                readyLast.next = current;
            }
            readyLast = current;
            current = next;
        }

        if (firstTask == null) {
            lastTask = null;
        }
        if (fullScan) {
            outOfOrder = !keptMonotone;
        }
        return readyFirst;
    }

    static final class TaskNode {
        private int transaction;
        private Runnable runnable;
        private Runnable nextTransactionRunnable;
        private TaskNode next;

        private TaskNode(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
            this.transaction = transaction;
            this.runnable = runnable;
            this.nextTransactionRunnable = nextTransactionRunnable;
        }

        Runnable runnable() {
            return runnable;
        }

        TaskNode next() {
            return next;
        }

        void clear() {
            runnable = null;
            nextTransactionRunnable = null;
            next = null;
        }

        TaskNode clearAndLink(TaskNode released) {
            clear();
            next = released;
            return this;
        }
    }
}
