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
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** A lease is reused only once ended; a borrower of an ended generation never reads a later frame. */
class FrameLeaseLenderTest {

    /** One borrow: the lease, the generation it named and the frame that generation lent. */
    private record Borrow(FrameLease lease, int generation, ByteBuf frame) {
        static Borrow of(FrameLease lease, ByteBuf frame) {
            assertSame(frame, lease.frame());
            return new Borrow(lease, lease.generation(), frame);
        }

        void assertLive() {
            assertTrue(lease.live(generation));
            assertSame(frame, lease.frame(generation));
        }

        void assertEnded() {
            assertFalse(lease.live(generation));
            var failure = assertThrows(IllegalStateException.class, () -> lease.frame(generation));
            assertEquals("Borrowed frame bytes read after their frame was released", failure.getMessage());
        }
    }

    @Test
    void aTakenLeaseIsLentAgainOnlyUnderANewGeneration() {
        var lender = new FrameLease.Lender();
        Set<FrameLease> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var stale = new ArrayList<Borrow>();
        for (int round = 0; round < 100; round++) {
            ByteBuf outerFrame = Unpooled.buffer(1), innerFrame = Unpooled.buffer(1);
            var outer = lender.open(outerFrame);
            assertSame(outer, lender.lease(outerFrame));
            var outerBorrow = Borrow.of(outer, outerFrame);
            lender.close(outer);
            var inner = lender.open(innerFrame);
            assertNotSame(outer, inner);
            Borrow innerBorrow = null;
            if (round % 2 == 0) {
                assertSame(inner, lender.lease(innerFrame));
                innerBorrow = Borrow.of(inner, innerFrame);
            }
            assertNull(lender.lease(outerFrame));
            for (Borrow borrow : stale) borrow.assertEnded();
            outerBorrow.assertLive();
            if (innerBorrow != null) innerBorrow.assertLive();
            lender.close(inner);
            lender.end(inner);
            lender.end(outer);
            outerBorrow.assertEnded();
            stale.add(outerBorrow);
            if (innerBorrow != null) {
                innerBorrow.assertEnded();
                stale.add(innerBorrow);
            }
            seen.add(outer);
            seen.add(inner);
        }
        assertEquals(2, seen.size(), "taken leases are reused");
        for (Borrow borrow : stale) borrow.assertEnded();
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
    void aSecondEndChangesNothing() {
        var lender = new FrameLease.Lender();
        ByteBuf a = Unpooled.buffer(1), b = Unpooled.buffer(1), c = Unpooled.buffer(1);
        var first = lender.open(a);
        lender.close(first);
        var second = lender.open(b);
        lender.close(second);
        lender.end(first);
        lender.end(second);
        var reopened = lender.open(c);
        lender.end(second);
        var nested = lender.open(a);
        var deeper = lender.open(b);
        assertNotSame(nested, deeper);
        assertNotSame(reopened, nested);
        assertNotSame(reopened, deeper);
    }

    @Test
    void aLeaseAtTheLastGenerationIsNeverLentAgain() throws ReflectiveOperationException {
        var lender = new FrameLease.Lender();
        ByteBuf frame = Unpooled.buffer(1);
        var lease = lender.open(frame);
        lender.close(lease);
        lender.end(lease);
        var field = FrameLease.class.getDeclaredField("generation");
        field.setAccessible(true);
        field.setInt(lease, Integer.MAX_VALUE - 1);
        assertSame(lease, lender.open(frame));
        var last = Borrow.of(lease, frame);
        assertEquals(Integer.MAX_VALUE, last.generation());
        lender.close(lease);
        lender.end(lease);
        last.assertEnded();
        var next = lender.open(frame);
        assertNotSame(lease, next);
        assertTrue(next.generation() > 0);
        last.assertEnded();
    }

    @Test
    void readsThatThrowStillEndTheirBorrows() {
        var lender = new FrameLease.Lender();
        var stale = new ArrayList<Borrow>();
        for (int round = 0; round < 50; round++) {
            ByteBuf frame = Unpooled.buffer(1);
            FrameLease lease = null;
            try {
                lease = lender.open(frame);
                try {
                    stale.add(Borrow.of(lender.lease(frame), frame));
                    if (round % 3 == 0) throw new IllegalArgumentException("malformed");
                } finally {
                    lender.close(lease);
                }
            } catch (IllegalArgumentException expected) {
                // The frame's owner still ends the lease below.
            } finally {
                if (lease != null) lender.end(lease);
            }
            for (Borrow borrow : stale) borrow.assertEnded();
        }
    }

    @Test
    void randomSequencesNeverLendAnOpenLeaseNorShowAStaleBorrowerALaterFrame() {
        var random = new SplittableRandom(0x1EA5EL);
        var lender = new FrameLease.Lender();
        var open = new ArrayList<FrameLease>();
        var closed = new ArrayList<FrameLease>();
        var frames = new ArrayList<ByteBuf>();
        var live = new ArrayList<Borrow>();
        var ended = new ArrayList<Borrow>();
        Set<FrameLease> reused = Collections.newSetFromMap(new IdentityHashMap<>());
        int reopenedTaken = 0;
        for (int step = 0; step < 200_000; step++) {
            int action = random.nextInt(5);
            if (action == 0 && open.size() < 4) {
                ByteBuf frame = Unpooled.buffer(1);
                var lease = lender.open(frame);
                assertFalse(open.contains(lease) || closed.contains(lease));
                assertTrue(lease.live());
                assertSame(frame, lease.frame());
                if (!reused.add(lease)) {
                    for (Borrow borrow : ended) {
                        if (borrow.lease() == lease) {
                            reopenedTaken++;
                            break;
                        }
                    }
                }
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
                moveEnded(live, ended, lease);
            } else if (action == 3 && !open.isEmpty()) {
                var lease = lender.lease(frames.getLast());
                assertSame(open.getLast(), lease);
                live.add(Borrow.of(lease, frames.getLast()));
            } else if (action == 4) {
                if (!live.isEmpty()) live.get(random.nextInt(live.size())).assertLive();
                if (!ended.isEmpty()) ended.get(random.nextInt(ended.size())).assertEnded();
            }
            if (ended.size() > 4096) ended.subList(0, 2048).clear();
        }
        for (Borrow borrow : live) borrow.assertLive();
        for (Borrow borrow : ended) borrow.assertEnded();
        assertTrue(reopenedTaken > 1000, "taken leases lent again " + reopenedTaken);
    }

    private static void moveEnded(List<Borrow> live, List<Borrow> ended, FrameLease lease) {
        for (int i = live.size() - 1; i >= 0; i--) {
            if (live.get(i).lease() == lease) {
                var borrow = live.remove(i);
                borrow.assertEnded();
                ended.add(borrow);
            }
        }
    }
}
