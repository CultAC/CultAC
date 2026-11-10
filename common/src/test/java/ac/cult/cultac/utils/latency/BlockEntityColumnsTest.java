package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.cultac.protocol.value.BlockPos;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Forgetting a column leaves the block entities exactly as a scan of every entry does, iteration order included. */
class BlockEntityColumnsTest {
    @Test
    void forgettingAColumnMatchesTheFullScan() throws Exception {
        var registry = DataTables.defaults().registry();
        int chest = registry.block("minecraft:chest").defaultState();
        int shulker = registry.block("minecraft:shulker_box").defaultState();
        int stone = registry.block("minecraft:stone").defaultState();
        var counted = new CompensatedBlockEntities(InteractionRegistries::defaults);
        var scanned = new CompensatedBlockEntities(InteractionRegistries::defaults);
        var random = new Random(3);
        int forgotten = 0, most = 0;
        for (int step = 0; step < 20000; step++) {
            // Columns -3..3 on both axes; positions straddle the negative boundary.
            var pos = new BlockPos(random.nextInt(112) - 56, random.nextInt(40) - 20, random.nextInt(112) - 56);
            switch (random.nextInt(8)) {
                case 0, 1, 2 -> {
                    counted.snapshot(pos, chest);
                    scanned.snapshot(pos, chest);
                }
                case 3 -> {
                    counted.blockEvent(pos, shulker, 1, 1);
                    scanned.blockEvent(pos, shulker, 1, 1);
                }
                case 4 -> {
                    counted.stateChanged(pos, chest, stone);
                    scanned.stateChanged(pos, chest, stone);
                }
                case 5 -> {
                    counted.tick();
                    scanned.tick();
                }
                case 6 -> {
                    int x = random.nextInt(9) - 4, z = random.nextInt(9) - 4;
                    counted.forgetChunk(x, z);
                    scanForget(scanned, x, z);
                    forgotten++;
                }
                default -> {
                    if (random.nextInt(200) == 0) {
                        counted.clear();
                        scanned.clear();
                    }
                }
            }
            assertSameEntries(scanned, counted);
            var entries = (Long2ObjectOpenHashMap<?>) field(counted, "entries");
            if (entries != null) most = Math.max(most, entries.size());
        }
        assertTrue(forgotten > 1000);
        assertTrue(most > 100, "entries never grew: " + most);
    }

    /** The column scan every forget ran before columns were counted. */
    private static void scanForget(CompensatedBlockEntities entities, int x, int z) throws Exception {
        var entries = (Long2ObjectOpenHashMap<?>) field(entities, "entries");
        if (entries == null) return;
        var ticking = (LongOpenHashSet) field(entities, "ticking");
        var added = (Long2LongOpenHashMap) field(entities, "added");
        var iterator = entries.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            long key = iterator.next().getLongKey();
            if (((int) (key >> 38) >> 4) == x && ((int) (key << 26 >> 38) >> 4) == z) {
                iterator.remove();
                ticking.remove(key);
                added.remove(key);
            }
        }
    }

    private static void assertSameEntries(CompensatedBlockEntities expected, CompensatedBlockEntities actual)
            throws Exception {
        for (String name : List.of("entries", "ticking", "added")) {
            Object want = field(expected, name), got = field(actual, name);
            if (want == null || got == null) {
                assertSame(want, got, name);
                continue;
            }
            assertEquals(want, got, name);
            assertEquals(order(want), order(got), name + " order");
        }
    }

    private static List<Object> order(Object collection) {
        var order = new ArrayList<Object>();
        if (collection instanceof Long2ObjectOpenHashMap<?> map) map.long2ObjectEntrySet().forEach(e -> order.add(e.getLongKey()));
        else if (collection instanceof Long2LongOpenHashMap map) map.keySet().forEach(order::add);
        else ((LongOpenHashSet) collection).forEach(order::add);
        return order;
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
