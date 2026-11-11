package ac.cult.cultac.network.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** A lease is reused only once ended and never taken; an open or taken lease is never handed out again. */
class FrameLeaseLenderTest {

    @Test
    void aTakenLeaseIsNeverLentAgain() {
        var lender = new FrameLease.Lender();
        Set<FrameLease> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int round = 0; round < 100; round++) {
            ByteBuf outerFrame = Unpooled.buffer(1), innerFrame = Unpooled.buffer(1);
            var outer = lender.open(outerFrame);
            assertSame(outer, lender.lease(outerFrame));
            lender.close(outer);
            var inner = lender.open(innerFrame);
            assertNotSame(outer, inner);
            if (round % 2 == 0) assertSame(inner, lender.lease(innerFrame));
            assertNull(lender.lease(outerFrame));
            lender.close(inner);
            lender.end(inner);
            lender.end(outer);
            seen.add(outer);
            seen.add(inner);
        }
        assertEquals(151, seen.size(), "every taken lease is new");
    }

    @Test
    void untakenNestedReadsAllocateNoLeaseOnceWarm() {
        var lender = new FrameLease.Lender();
        Set<FrameLease> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int round = 0; round < 100; round++) {
            ByteBuf outerFrame = Unpooled.buffer(1), innerFrame = Unpooled.buffer(1);
            var outer = lender.open(outerFrame);
            lender.close(outer);
            var inner = lender.open(innerFrame);
            assertNotSame(outer, inner);
            assertTrue(inner.live() && outer.live());
            lender.close(inner);
            lender.end(inner);
            lender.end(outer);
            assertFalse(inner.live() || outer.live());
            seen.add(outer);
            seen.add(inner);
        }
        assertEquals(2, seen.size());
    }

    @Test
    void randomSequencesNeverLendAnOpenOrTakenLease() {
        var random = new SplittableRandom(0x1EA5EL);
        var lender = new FrameLease.Lender();
        var open = new ArrayList<FrameLease>();
        var closed = new ArrayList<FrameLease>();
        var frames = new ArrayList<ByteBuf>();
        Set<FrameLease> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int step = 0; step < 200_000; step++) {
            int action = random.nextInt(4);
            if (action == 0 && open.size() < 4) {
                ByteBuf frame = Unpooled.buffer(1);
                var lease = lender.open(frame);
                assertFalse(open.contains(lease) || closed.contains(lease) || taken.contains(lease));
                assertTrue(lease.live());
                assertSame(frame, lease.frame());
                open.add(lease);
                frames.add(frame);
            } else if (action == 1 && !open.isEmpty()) {
                var lease = open.removeLast();
                frames.removeLast();
                lender.close(lease);
                closed.add(lease);
            } else if (action == 2 && !closed.isEmpty()) {
                var lease = closed.remove(random.nextInt(closed.size()));
                lender.end(lease);
                assertFalse(lease.live());
                assertThrows(IllegalStateException.class, lease::frame);
            } else if (action == 3 && !open.isEmpty()) {
                var lease = lender.lease(frames.getLast());
                assertSame(open.getLast(), lease);
                taken.add(lease);
            }
        }
        for (FrameLease lease : taken)
            if (!open.contains(lease) && !closed.contains(lease)) assertFalse(lease.live());
        assertTrue(taken.size() > 1000, "taken " + taken.size());
    }
}
