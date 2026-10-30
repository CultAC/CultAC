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
    @Test
    void takesTheNextKeptSizeAndExactlyThePastTheLargest() {
        assertEquals(1 << FrameBuffers.MIN_SHIFT, FrameBuffers.take(0).length);
        assertEquals(1 << FrameBuffers.MIN_SHIFT, FrameBuffers.take(1).length);
        assertEquals(256, FrameBuffers.take(256).length);
        assertEquals(512, FrameBuffers.take(257).length);
        assertEquals(4096, FrameBuffers.take(4000).length);
        assertEquals(1 << FrameBuffers.MAX_SHIFT, FrameBuffers.take(1 << FrameBuffers.MAX_SHIFT).length);
        assertEquals((1 << FrameBuffers.MAX_SHIFT) + 1, FrameBuffers.take((1 << FrameBuffers.MAX_SHIFT) + 1).length);
    }

    @Test
    void anArrayGivenBackIsTakenAgainOnce() {
        byte[] array = FrameBuffers.take(3000);
        FrameBuffers.give(array);
        Map<byte[], Boolean> taken = new IdentityHashMap<>();
        List<byte[]> held = new ArrayList<>();
        for (int i = 0; i < FrameBuffers.KEPT_BYTES / 4096 + 1; i++) {
            byte[] next = FrameBuffers.take(2049);
            assertEquals(null, taken.put(next, true));
            held.add(next);
        }
        assertSame(array, held.stream().filter(next -> next == array).findFirst().orElseThrow());
        held.forEach(FrameBuffers::give);
    }

    @Test
    void arraysNotItsOwnSizesAreLetGo() {
        byte[] odd = new byte[3000];
        byte[] small = new byte[128];
        byte[] large = new byte[(1 << FrameBuffers.MAX_SHIFT) * 2];
        FrameBuffers.give(odd);
        FrameBuffers.give(small);
        FrameBuffers.give(large);
        for (int i = 0; i < FrameBuffers.KEPT_BYTES / 256 + 1; i++) assertNotSame(small, FrameBuffers.take(100));
        assertNotSame(large, FrameBuffers.take(large.length));
    }
}
