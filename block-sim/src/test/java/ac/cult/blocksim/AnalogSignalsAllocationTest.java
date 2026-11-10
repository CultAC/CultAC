package ac.cult.blocksim;

import static ac.cult.blocksim.AnalogSignalsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.AnalogSignals;
import ac.cult.blocksim.engine.ParentAnalogSignals;
import ac.cult.blocksim.engine.SimLevel;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * Repeated construction against one unchanged BlockRegistry must not redo the whole-registry hierarchy
 * classification. The parent splits every block's classHierarchy (about 1,300 blocks) per construction,
 * hundreds of KiB; per-instance work (the instance, its ItemTemplates and bindings) is a few dozen bytes.
 */
class AnalogSignalsAllocationTest {
    private static final long BUDGET_BYTES_PER_CONSTRUCTION = 32 * 1024;
    private static final int WARMUP = 512, MEASURED = 256;

    @Test void repeatedSameRegistryConstructionStaysWithinAllocationBudget() {
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(threads.isThreadAllocatedMemorySupported(), "Thread allocation accounting unsupported");
        if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
        var level = bareLevel(new World(), DATA.registry());
        int chest = state("chest"), stone = state("stone");
        var probe = new AnalogSignals(DATA, ITEMS, REGISTRIES);
        var parentProbe = new ParentAnalogSignals(DATA, ITEMS, REGISTRIES);
        assertTrue(probe.hasOutput(level, chest) && parentProbe.hasOutput(level, chest), "Setup: chest must classify");
        assertFalse(probe.hasOutput(level, stone) || parentProbe.hasOutput(level, stone), "Setup: stone must not classify");

        var retained = new AnalogSignals[8];
        long checksum = 0;
        for (int i = 0; i < WARMUP; i++) {
            var signals = new AnalogSignals(DATA, ITEMS, REGISTRIES);
            retained[i % retained.length] = signals;
            if (signals.hasOutput(level, chest)) checksum++;
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MEASURED; i++) {
            var signals = new AnalogSignals(DATA, ITEMS, REGISTRIES);
            retained[i % retained.length] = signals;
            if (signals.hasOutput(level, (i & 1) == 0 ? chest : stone)) checksum++;
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(WARMUP + MEASURED / 2, checksum, "Every constructed instance must classify chest and not stone");
        for (var signals : retained) assertNotNull(signals);

        long perConstruction = allocated / MEASURED;
        long parentPerConstruction = parentBytesPerConstruction(threads, level, chest);
        assertTrue(perConstruction <= BUDGET_BYTES_PER_CONSTRUCTION, () -> "AnalogSignals same-registry construction allocated "
            + perConstruction + " bytes/op over " + MEASURED + " warmed constructions (total " + allocated + ", budget "
            + BUDGET_BYTES_PER_CONSTRUCTION + "); parent oracle " + parentPerConstruction + " bytes/op");
    }

    private static long parentBytesPerConstruction(ThreadMXBean threads, SimLevel level, int chest) {
        var retained = new ParentAnalogSignals[8];
        long checksum = 0;
        for (int i = 0; i < WARMUP; i++) {
            retained[i % retained.length] = new ParentAnalogSignals(DATA, ITEMS, REGISTRIES);
            if (retained[i % retained.length].hasOutput(level, chest)) checksum++;
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MEASURED; i++) {
            retained[i % retained.length] = new ParentAnalogSignals(DATA, ITEMS, REGISTRIES);
            if (retained[i % retained.length].hasOutput(level, chest)) checksum++;
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(WARMUP + MEASURED, checksum);
        return allocated / MEASURED;
    }
}
