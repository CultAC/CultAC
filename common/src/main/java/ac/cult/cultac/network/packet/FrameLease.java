package ac.cult.cultac.network.packet;

import io.netty.buffer.ByteBuf;
import java.lang.invoke.VarHandle;

/**
 * A frame's bytes lent to the values decoded from it, valid until the frame's owner ends the lease just before it
 * releases or hands off the frame. The lease holds no reference count: a borrower that keeps bytes past the frame
 * copies them first, see {@link EntityMetadata.Entry#detach()}. A lender reuses a lease object for later frames; a
 * borrower names the generation it borrowed, and a lease of any other generation reads as ended.
 */
public final class FrameLease {
    private ByteBuf frame;
    /** The last generation {@link Lender#open} began. */
    private int generation;
    /** {@link #generation} while that read's borrows last, else 0. */
    private volatile int live;
    private FrameLease outer;

    private FrameLease() {}

    /** The frame, which must not be released while this returns it. */
    ByteBuf frame() {
        ByteBuf lent = frame;
        if (live == 0 || lent == null)
            throw new IllegalStateException("Borrowed frame bytes read after their frame was released");
        return lent;
    }

    /** As {@link #frame()}, for a borrower of {@code generation}. */
    ByteBuf frame(int generation) {
        if (live != generation || generation == 0) throw released();
        ByteBuf lent = frame;
        VarHandle.loadLoadFence();
        if (lent == null || live != generation) throw released();
        return lent;
    }

    /** The generation a borrower that calls this while {@link #frame()} returns borrows. */
    int generation() {
        return generation;
    }

    boolean live() {
        return live != 0;
    }

    boolean live(int generation) {
        return generation != 0 && live == generation;
    }

    private static IllegalStateException released() {
        return new IllegalStateException("Borrowed frame bytes read after their frame was released");
    }

    /** Supplies the lease on the frame a codec is reading, when that frame's owner lends it. */
    public interface Source {
        /** The lease on {@code frame}, or null when {@code frame} is not lent; the caller then copies. */
        FrameLease lease(ByteBuf frame);
    }

    /** Lends one connection's frames to codecs, one read at a time. Owner thread only. */
    public static final class Lender implements Source {
        private FrameLease current;
        /** Ended leases for the next reads; a nested read takes the second. */
        private FrameLease spare, nestedSpare;

        /** Lends {@code frame} to the reads before the matching {@link #close}; reads may nest. */
        public FrameLease open(ByteBuf frame) {
            FrameLease lease = spare;
            if (lease != null) spare = null;
            else {
                lease = nestedSpare;
                if (lease == null) lease = new FrameLease();
                else nestedSpare = null;
            }
            lease.frame = frame;
            lease.outer = current;
            current = lease;
            int generation = lease.generation + 1;
            lease.generation = generation;
            lease.live = generation;
            return lease;
        }

        /** Ends the read {@link #open} began; values it decoded keep borrowing until {@link #end}. */
        public void close(FrameLease lease) {
            current = lease.outer;
            lease.outer = null;
        }

        /** Ends every borrow on the closed {@code lease}'s frame. */
        public void end(FrameLease lease) {
            if (lease.live == 0) return;
            lease.live = 0;
            VarHandle.storeStoreFence();
            lease.frame = null;
            // The last generation is never reissued, so no borrower of it can read a later frame.
            if (lease.generation == Integer.MAX_VALUE) return;
            if (spare == null) spare = lease;
            else if (spare != lease && nestedSpare == null) nestedSpare = lease;
        }

        @Override
        public FrameLease lease(ByteBuf frame) {
            FrameLease lease = current;
            if (lease == null || lease.frame != frame) return null;
            return lease;
        }
    }
}
