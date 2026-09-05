package ac.cult.cultac.utils.latency;

final class LatencyTaskQueue {
    private TaskNode firstTask;
    private TaskNode lastTask;

    synchronized void add(int transaction, Runnable runnable) {
        add(new TaskNode(transaction, runnable, null));
    }

    synchronized void addWithNextTransaction(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
        add(new TaskNode(transaction, runnable, nextTransactionRunnable));
    }

    private void add(TaskNode task) {
        if (lastTask == null) {
            firstTask = task;
        } else {
            lastTask.next = task;
        }
        lastTask = task;
    }

    synchronized TaskNode drainReady(int transaction) {
        TaskNode previous = null;
        TaskNode current = firstTask;
        TaskNode readyFirst = null;
        TaskNode readyLast = null;

        while (current != null) {
            if (transaction + 1 < current.transaction) {
                break;
            }

            TaskNode next = current.next;
            if (transaction == current.transaction - 1) {
                previous = current;
                current = next;
                continue;
            }

            TaskNode continuation = current.nextTransactionTask();
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
        return readyFirst;
    }

    static final class TaskNode {
        private final int transaction;
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

        private TaskNode nextTransactionTask() {
            if (nextTransactionRunnable == null) {
                return null;
            }

            TaskNode task = new TaskNode(transaction + 1, nextTransactionRunnable, null);
            nextTransactionRunnable = null;
            return task;
        }

        void clear() {
            runnable = null;
            nextTransactionRunnable = null;
            next = null;
        }
    }
}
