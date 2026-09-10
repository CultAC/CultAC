package ac.cult.cultac.utils.latency;

/** Geyser's callback FIFO, with a cursor separating written packets from queued packets. */
public final class GeyserQueue extends java.util.AbstractQueue<Runnable> {
    private static final class Entry {
        final Runnable callback;
        Entry next;

        Entry(Runnable callback) {
            this.callback = java.util.Objects.requireNonNull(callback);
        }
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
        Entry entry = lastWritten == null ? first : lastWritten.next;
        if (entry == null) throw new IllegalStateException("Latency write has no Geyser callback");
        writePacket.run();
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
    public synchronized java.util.Iterator<Runnable> iterator() {
        var callbacks = new java.util.ArrayList<Runnable>();
        for (Entry entry = first; entry != null; entry = entry.next) callbacks.add(entry.callback);
        return java.util.Collections.unmodifiableList(callbacks).iterator();
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
