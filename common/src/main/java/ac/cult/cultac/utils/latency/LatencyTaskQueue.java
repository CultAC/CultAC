package ac.cult.cultac.utils.latency;

final class LatencyTaskQueue {
    private static final int MAX_RECYCLED_TASKS = 256;
    private static final int RUNS = 4;
    // Each run keeps its tasks in insertion order with non-decreasing transactions, so its ready tasks are a prefix.
    private final TaskNode[] runFirst = new TaskNode[RUNS];
    private final TaskNode[] runLast = new TaskNode[RUNS];
    // Tasks no run could take, in insertion order with any transactions.
    private TaskNode firstTask;
    private TaskNode lastTask;
    // Insertion order across the runs and the overflow; a continuation keeps its task's sequence.
    private long nextSequence;
    private TaskNode recycledTasks;
    private int recycledTaskCount;

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
        continuation.sequence = task.sequence;
        task.nextTransactionRunnable = null;
        return continuation;
    }

    private void add(TaskNode task) {
        task.sequence = nextSequence++;
        for (int run = 0; run < RUNS; run++) {
            TaskNode last = runLast[run];
            if (last == null) {
                runFirst[run] = task;
                runLast[run] = task;
                return;
            }
            if (last.transaction <= task.transaction) {
                last.next = task;
                runLast[run] = task;
                return;
            }
        }
        if (lastTask == null) {
            firstTask = task;
        } else {
            lastTask.next = task;
        }
        lastTask = task;
    }

    synchronized TaskNode drainReady(int transaction) {
        if (runFirst[1] == null && runFirst[2] == null && runFirst[3] == null && firstTask == null) {
            return drainRun(0, transaction);
        }
        TaskNode ready = null;
        for (int run = 0; run < RUNS; run++) ready = merge(ready, drainRun(run, transaction));
        return merge(ready, drainOverflow(transaction));
    }

    // Chains never share a sequence: a continuation stays in its task's chain, so merging pairwise keeps the order.
    private static TaskNode merge(TaskNode a, TaskNode b) {
        if (a == null) return b;
        if (b == null) return a;
        TaskNode first;
        if (a.sequence < b.sequence) {
            first = a;
            a = a.next;
        } else {
            first = b;
            b = b.next;
        }
        TaskNode last = first;
        while (a != null && b != null) {
            if (a.sequence < b.sequence) {
                last.next = a;
                last = a;
                a = a.next;
            } else {
                last.next = b;
                last = b;
                b = b.next;
            }
        }
        last.next = a != null ? a : b;
        return first;
    }

    /**
     * The run's ready prefix in order, a continuation that is also ready right after its task. Continuations that are
     * not ready take their task's place; they are due at transaction + 1, no later than any task left in the run.
     */
    private TaskNode drainRun(int run, int transaction) {
        TaskNode current = runFirst[run];
        if (current == null || transaction < current.transaction) return null;
        TaskNode readyFirst = null;
        TaskNode readyLast = null;
        TaskNode keptFirst = null;
        TaskNode keptLast = null;
        while (current != null && transaction >= current.transaction) {
            TaskNode next = current.next;
            current.next = null;
            if (readyFirst == null) {
                readyFirst = current;
            } else {
                readyLast.next = current;
            }
            readyLast = current;
            TaskNode continuation = nextTransactionTask(current);
            if (continuation != null) {
                if (continuation.transaction <= transaction) {
                    current.next = continuation;
                    readyLast = continuation;
                } else if (keptFirst == null) {
                    keptFirst = continuation;
                    keptLast = continuation;
                } else {
                    keptLast.next = continuation;
                    keptLast = continuation;
                }
            }
            current = next;
        }
        if (keptFirst == null) {
            runFirst[run] = current;
            if (current == null) runLast[run] = null;
        } else {
            keptLast.next = current;
            runFirst[run] = keptFirst;
            if (current == null) runLast[run] = keptLast;
        }
        return readyFirst;
    }

    private TaskNode drainOverflow(int transaction) {
        TaskNode previous = null;
        TaskNode current = firstTask;
        TaskNode readyFirst = null;
        TaskNode readyLast = null;

        while (current != null) {
            TaskNode next = current.next;
            if (transaction < current.transaction) {
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
        if (readyLast != null) readyLast.next = null;
        return readyFirst;
    }

    static final class TaskNode {
        private int transaction;
        private long sequence;
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
