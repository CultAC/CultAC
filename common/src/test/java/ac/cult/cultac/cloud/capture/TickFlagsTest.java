package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.wire.IntakeWire;
import java.util.List;
import org.junit.jupiter.api.Test;

class TickFlagsTest {
    @Test
    void aTickKeepsAtMostTheCap() {
        TickFlags flags = new TickFlags();
        assertTrue(flags.drain().isEmpty());
        for (int i = 0; i < IntakeWire.MAX_FLAGS + 5; i++) flags.add("Reach", i);
        List<IntakeWire.Flag> drained = flags.drain();
        assertEquals(IntakeWire.MAX_FLAGS, drained.size());
        assertEquals(IntakeWire.Flag.of("Reach", 0), drained.get(0));
        assertTrue(flags.drain().isEmpty());
    }
}
