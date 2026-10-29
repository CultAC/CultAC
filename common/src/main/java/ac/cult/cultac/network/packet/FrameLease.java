package ac.cult.cultac.network.packet;

import io.netty.buffer.ByteBuf;

/**
 * A frame's bytes lent to the values decoded from it, valid until the frame's owner ends the lease just before it
 * releases or hands off the frame. The lease holds no reference count: a borrower that keeps bytes past the frame
 * copies them first, see {@link EntityMetadata.Entry#detach()}.
 */
public final class FrameLease {
    private ByteBuf frame;
    private boolean taken;
    private volatile boolean live;
    private FrameLease outer;

    private FrameLease() {}

    /** The frame, which must not be released while this returns it. */
    ByteBuf frame() {
        ByteBuf lent = frame;
        if (!live || lent == null)
            throw new IllegalStateException("Borrowed frame bytes read after their frame was released");
        return lent;
    }

    boolean live() {
        return live;
    }

    /** Supplies the lease on the frame a codec is reading, when that frame's owner lends it. */
    public interface Source {
        /** The lease on {@code frame}, or null when {@code frame} is not lent; the caller then copies. */
        FrameLease lease(ByteBuf frame);
    }

    /** Lends one connection's frames to codecs, one read at a time. Owner thread only. */
    public static final class Lender implements Source {
        private FrameLease current;
        private FrameLease spare;

        /** Lends {@code frame} to the reads before the matching {@link #close}; reads may nest. */
        public FrameLease open(ByteBuf frame) {
            FrameLease lease = spare;
            if (lease == null) lease = new FrameLease();
            else spare = null;
            lease.frame = frame;
            lease.taken = false;
            lease.live = true;
            lease.outer = current;
            current = lease;
            return lease;
        }

        /** Ends the read {@link #open} began; values it decoded keep borrowing until {@link #end}. */
        public void close(FrameLease lease) {
            current = lease.outer;
            lease.outer = null;
        }

        /** Ends every borrow on the closed {@code lease}'s frame. */
        public void end(FrameLease lease) {
            lease.live = false;
            lease.frame = null;
            if (!lease.taken) spare = lease;
        }

        @Override
        public FrameLease lease(ByteBuf frame) {
            FrameLease lease = current;
            if (lease == null || lease.frame != frame) return null;
            lease.taken = true;
            return lease;
        }
    }
}
