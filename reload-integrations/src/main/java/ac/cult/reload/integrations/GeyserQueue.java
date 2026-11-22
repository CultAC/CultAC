package ac.cult.reload.integrations;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;

/** Geyser's callback FIFO, with a cursor separating written packets from queued packets.
 * Geyser can register callbacks off the tick loop. Registration also precedes Cloudburst's
 * queued write, so a shared executor alone does not establish the callback's wire order. */
public final class GeyserQueue extends AbstractQueue<Runnable> {
    /** Completed receipts are swept once the set doubles, keeping each write amortized O(1). */
    private static final int PRUNE_FLOOR = 64;

    private static final class Entry {
        Runnable callback;
        Entry next;

        Entry(Runnable callback) {
            this.callback = Objects.requireNonNull(callback);
        }
    }

    /** Host-owned indirection also fences callbacks already polled by Geyser onto another executor. */
    private static final class Receipt implements Runnable {
        private volatile Runnable callback;

        Receipt(Runnable callback) {
            this.callback = callback;
        }

        public void run() {
            var action = callback;
            callback = null;
            if (action != null) action.run();
        }
    }

    /** One tap's engine callbacks. Closing preserves their wire slots and every native Geyser callback. */
    public final class Scope implements AutoCloseable {
        private final Set<Receipt> receipts = Collections.newSetFromMap(new IdentityHashMap<>());
        private int pruneAt = PRUNE_FLOOR;
        private boolean closed;

        private Receipt receipt(Runnable callback) {
            if (closed) throw new IllegalStateException("Latency scope is closed");
            if (receipts.size() >= pruneAt) {
                receipts.removeIf(value -> value.callback == null);
                pruneAt = Math.max(PRUNE_FLOOR, receipts.size() * 2);
            }
            var receipt = new Receipt(Objects.requireNonNull(callback));
            receipts.add(receipt);
            return receipt;
        }

        public void insert(Runnable callback, Runnable writePacket) {
            synchronized (GeyserQueue.this) {
                GeyserQueue.this.insert(receipt(callback), writePacket);
            }
        }

        public void writeWithReceipt(Supplier<Runnable> writePacket) {
            synchronized (GeyserQueue.this) {
                GeyserQueue.this.writeWithReceipt(() -> {
                    Runnable callback = writePacket.get();
                    return callback == null ? null : receipt(callback);
                });
            }
        }

        public void close() {
            synchronized (GeyserQueue.this) {
                closed = true;
                receipts.forEach(value -> value.callback = null);
                receipts.clear();
            }
        }
    }

    public Scope scope() {
        return new Scope();
    }

    private Queue<Runnable> originalQueue;

    public void originalQueue(Queue<Runnable> queue) {
        originalQueue = queue;
    }

    public Queue<Runnable> originalQueue() {
        return originalQueue;
    }

    private Entry first;
    private Entry last;
    private Entry lastWritten;
    private boolean tracking = true;

    @Override
    public synchronized boolean offer(Runnable callback) {
        Entry entry = new Entry(callback);
        if (last == null) first = entry;
        else last.next = entry;
        last = entry;
        if (!tracking) lastWritten = entry;
        return true;
    }

    /** Mark the next callback registered by Geyser only when its packet is written. */
    public synchronized void write(Runnable writePacket) {
        writeWithReceipt(() -> {
            writePacket.run();
            return null;
        });
    }

    /** Attach an optional local receipt to this exact successful write, before Geyser's callback. */
    public synchronized void writeWithReceipt(Supplier<Runnable> writePacket) {
        Entry entry = lastWritten == null ? first : lastWritten.next;
        if (entry == null) throw new IllegalStateException("Latency write has no Geyser callback");
        Runnable receipt = writePacket.get();
        if (receipt != null) {
            Runnable callback = entry.callback;
            entry.callback = () -> {
                try {
                    receipt.run();
                } finally {
                    callback.run();
                }
            };
        }
        lastWritten = entry;
    }

    /** Insert Cult's packet and callback before callbacks for Geyser packets still awaiting write. */
    public synchronized void insert(Runnable callback, Runnable writePacket) {
        Entry entry = new Entry(callback);
        writePacket.run();
        entry.next = lastWritten == null ? first : lastWritten.next;
        if (lastWritten == null) first = entry;
        else lastWritten.next = entry;
        if (entry.next == null) last = entry;
        lastWritten = entry;
    }

    @Override
    public synchronized Runnable poll() {
        if (lastWritten == null) return null;
        Entry entry = first;
        first = entry.next;
        entry.next = null;
        if (lastWritten == entry) lastWritten = null;
        if (first == null) last = null;
        return entry.callback;
    }

    @Override
    public synchronized Runnable peek() {
        return lastWritten == null ? null : first.callback;
    }

    @Override
    public synchronized int size() {
        int size = 0;
        for (Entry entry = first; entry != null; entry = entry.next) size++;
        return size;
    }

    @Override
    public synchronized Iterator<Runnable> iterator() {
        var callbacks = new ArrayList<Runnable>();
        for (Entry entry = first; entry != null; entry = entry.next) callbacks.add(entry.callback);
        return Collections.unmodifiableList(callbacks).iterator();
    }

    @Override
    public synchronized void clear() {
        first = last = lastWritten = null;
    }

    /** Preserve Geyser's normal FIFO behavior if the outbound observer is detached. */
    public synchronized void stopTrackingWrites() {
        tracking = false;
        lastWritten = last;
    }

    public synchronized boolean isTrackingWrites() {
        return tracking;
    }
}
