package ac.cult.cultac.player;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.jetbrains.annotations.NotNull;

/**
 * Written pings awaiting their pong, in wire order, linked through the entries themselves, so an entry goes in
 * after any entry the queue holds without a walk. An entry is in at most one queue at a time, and a polled entry
 * holds no links. Confined to its player's lock.
 */
final class PendingTransactions<E extends PendingTransactions.Link> extends AbstractQueue<E> {
    /** An entry of a {@link PendingTransactions}. */
    abstract static class Link {
        private PendingTransactions<?> queue;
        private Link next;
    }

    private Link head, tail;
    private int size;

    @Override
    public boolean offer(E entry) {
        link(entry, tail);
        return true;
    }

    void addFirst(E entry) {
        link(entry, null);
    }

    /** Adds {@code entry} right behind {@code previous}, which this queue holds. */
    void addAfter(Link previous, E entry) {
        if (previous.queue != this) throw new IllegalArgumentException("Not in this queue");
        link(entry, previous);
    }

    private void link(Link entry, Link previous) {
        if (entry.queue != null) throw new IllegalArgumentException("Already queued");
        Link next = previous == null ? head : previous.next;
        entry.queue = this;
        entry.next = next;
        if (previous == null) head = entry;
        else previous.next = entry;
        if (next == null) tail = entry;
        size++;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E poll() {
        Link oldest = head;
        if (oldest == null) return null;
        head = oldest.next;
        if (head == null) tail = null;
        oldest.queue = null;
        oldest.next = null;
        size--;
        return (E) oldest;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E peek() {
        return (E) head;
    }

    @Override
    public boolean contains(Object entry) {
        return entry instanceof Link link && link.queue == this;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public @NotNull Iterator<E> iterator() {
        return new Iterator<>() {
            private Link next = head;

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            @SuppressWarnings("unchecked")
            public E next() {
                Link current = next;
                if (current == null) throw new NoSuchElementException();
                next = current.next;
                return (E) current;
            }
        };
    }
}
