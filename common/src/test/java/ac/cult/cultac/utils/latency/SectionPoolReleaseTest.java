package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Releasing a section changes a pool only when the pool holds that section, and then by one reference. */
class SectionPoolReleaseTest {

    @Test
    void aReleaseChangesOnlyThePoolHoldingTheSection() {
        var random = new SplittableRandom(0x8E1L);
        String dimension = "test:release";
        var references = new IdentityHashMap<CachedSection, Integer>();
        var sections = new ArrayList<CachedSection>();
        var positions = new IdentityHashMap<CachedSection, int[]>();
        int released = 0, ignored = 0;
        for (int step = 0; step < 20000; step++) {
            int x = random.nextInt(3), index = random.nextInt(2), z = random.nextInt(3);
            var pool = SectionPool.forChunk(dimension, x, index, z);
            int action = random.nextInt(6);
            if (action <= 1 || sections.isEmpty()) {
                var section = new CachedSection(randomSection(random));
                if (section.isEmpty() || section.states().isUniform()) continue;
                var result = pool.internRetained(section);
                references.merge(result, 1, Integer::sum);
                positions.putIfAbsent(result, new int[] {x, index, z});
                if (!sections.contains(result)) sections.add(result);
            } else {
                var section = sections.get(random.nextInt(sections.size()));
                int[] at = action == 2 ? new int[] {x, index, z} : positions.get(section);
                var target = action == 3
                        ? new CachedSection(section.states().copy())
                        : action == 4 ? SectionPool.canonicalAir() : section;
                int before = pooledCount(dimension);
                boolean holds = target == section
                        && references.getOrDefault(section, 0) > 0
                        && Arrays.equals(at, positions.get(section));
                SectionPool.releaseSectionReference(dimension, at[0], at[1], at[2], target);
                if (holds) {
                    int left = references.merge(section, -1, Integer::sum);
                    assertEquals(left > 0, section.isShared(), "step " + step);
                    if (left > 0) assertEquals(left, section.poolReferences(), "step " + step);
                    else {
                        references.remove(section);
                        sections.remove(section);
                        positions.remove(section);
                        assertEquals(before - 1, pooledCount(dimension), "step " + step);
                    }
                    released++;
                } else {
                    assertEquals(before, pooledCount(dimension), "step " + step);
                    ignored++;
                }
            }
            for (var held : sections) {
                assertTrue(held.isShared(), "step " + step);
                assertEquals((int) references.get(held), held.poolReferences(), "step " + step);
            }
        }
        assertTrue(released > 3000, "released " + released);
        assertTrue(ignored > 3000, "ignored " + ignored);
    }

    private static int pooledCount(String dimension) {
        int count = 0;
        for (int x = 0; x < 3; x++)
            for (int index = 0; index < 2; index++)
                for (int z = 0; z < 3; z++) {
                    var pool = SectionPool.existing(dimension, x, index, z);
                    if (pool != null) count += pool.nonUniformPoolSize();
                }
        return count;
    }

    private static PalettedSection randomSection(SplittableRandom random) {
        var section = PalettedSection.readBlocks(Unpooled.wrappedBuffer(new byte[] {0, 0}))
                .owned();
        int entries = 1 + random.nextInt(3);
        for (int i = 0, writes = 1 + random.nextInt(3); i < writes; i++)
            section.getAndSet(random.nextInt(4), 1 + random.nextInt(entries));
        return section;
    }
}
