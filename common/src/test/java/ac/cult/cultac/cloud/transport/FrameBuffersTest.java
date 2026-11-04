package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrameBuffersTest {
    private final FrameBuffers frames = new FrameBuffers();

    @Test
    void takesTheNextKeptSizeAndExactlyThePastTheLargest() {
        assertEquals(1 << FrameBuffers.MIN_SHIFT, frames.take(0).length);
        assertEquals(1 << FrameBuffers.MIN_SHIFT, frames.take(1).length);
        assertEquals(256, frames.take(256).length);
        assertEquals(512, frames.take(257).length);
        assertEquals(4096, frames.take(4000).length);
        assertEquals(1 << FrameBuffers.MAX_SHIFT, frames.take(1 << FrameBuffers.MAX_SHIFT).length);
        assertEquals((1 << FrameBuffers.MAX_SHIFT) + 1, frames.take((1 << FrameBuffers.MAX_SHIFT) + 1).length);
    }

    @Test
    void anArrayGivenBackIsTakenAgainOnce() {
        byte[] array = frames.take(3000);
        frames.give(array);
        Map<byte[], Boolean> taken = new IdentityHashMap<>();
        List<byte[]> held = new ArrayList<>();
        for (int i = 0; i < FrameBuffers.KEPT_BYTES / 4096 + 1; i++) {
            byte[] next = frames.take(2049);
            assertEquals(null, taken.put(next, true));
            held.add(next);
        }
        assertSame(array, held.get(0));
        held.forEach(frames::give);
    }

    @Test
    void eachSizeKeepsItsShareAndLetsTheRestGo() {
        List<byte[]> given = new ArrayList<>();
        for (int i = 0; i < FrameBuffers.KEPT_BYTES / 1024 + 1; i++) given.add(new byte[1024]);
        given.forEach(frames::give);
        Map<byte[], Boolean> kept = new IdentityHashMap<>();
        for (int i = 0; i < given.size(); i++) kept.put(frames.take(1000), true);
        assertEquals(
                FrameBuffers.KEPT_BYTES / 1024,
                given.stream().filter(kept::containsKey).count());
    }

    @Test
    void arraysNotItsOwnSizesAreLetGo() {
        byte[] odd = new byte[3000];
        byte[] small = new byte[128];
        byte[] large = new byte[(1 << FrameBuffers.MAX_SHIFT) * 2];
        frames.give(odd);
        frames.give(small);
        frames.give(large);
        for (int i = 0; i < FrameBuffers.KEPT_BYTES / 256 + 1; i++) assertNotSame(small, frames.take(100));
        assertNotSame(odd, frames.take(2049));
        assertNotSame(large, frames.take(large.length));
    }

    @Test
    void poolsShareNoArrays() {
        byte[] array = frames.take(600);
        frames.give(array);
        assertNotSame(array, new FrameBuffers().take(600));
        assertSame(array, frames.take(600));
    }
}
