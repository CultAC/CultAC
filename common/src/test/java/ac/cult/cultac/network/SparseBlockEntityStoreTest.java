package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.engine.BlockEntityPrototypes;
import ac.cult.blocksim.engine.ShulkerAnimation;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedBlockEntities;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SparseBlockEntityStoreTest {
    @Test
    void ordinaryBlockWritesDoNotAllocateAStore() throws Exception {
        var entities = new CompensatedBlockEntities(ac.cult.blocksim.data.InteractionRegistries::defaults);
        var registry = DataTables.defaults().registry();
        int stone = registry.block("minecraft:stone").defaultState();
        int air = registry.block("minecraft:air").defaultState();
        var field = CompensatedBlockEntities.class.getDeclaredField("entries");
        field.setAccessible(true);
        assertNull(field.get(entities));
        for (int i = 0; i < 1000; i++) {
            var pos = new BlockPos(i - 500, 64, -i);
            entities.stateChanged(pos, air, stone);
            assertNull(entities.snapshot(pos, stone));
        }
        entities.tick();
        entities.forgetChunk(-1, -1);
        assertNull(field.get(entities));
    }

    @Test
    void retainedAnimationsTickAndChunkCleanupUseTheSameSignedPositionKeys() throws Exception {
        var entities = new CompensatedBlockEntities(ac.cult.blocksim.data.InteractionRegistries::defaults);
        var registry = DataTables.defaults().registry();
        int shulker = registry.block("minecraft:shulker_box").defaultState();
        int stone = registry.block("minecraft:stone").defaultState();
        var first = new BlockPos(-17, -64, -33);
        var second = new BlockPos(32, 255, 16);
        entities.blockEvent(first, shulker, 1, 1);
        entities.blockEvent(second, shulker, 1, 1);
        var original = entities.snapshot(first, shulker);
        assertNotNull(original);
        entities.tick();
        var expected = ac.cult.blocksim.engine.ShulkerAnimation.tick(original);
        var actual = entities.snapshot(first, shulker);
        assertEquals(expected.type(), actual.type());
        assertEquals(expected.data().keys(), actual.data().keys());
        for (String key : expected.data().keys()) assertTrue(expected.data().sameValue(key, actual.data()), key);
        assertEquals(expected.savedData(), actual.savedData());
        entities.forgetChunk(first.getX() >> 4, first.getZ() >> 4);
        var field = CompensatedBlockEntities.class.getDeclaredField("entries");
        field.setAccessible(true);
        var retained = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?>) field.get(entities);
        assertFalse(retained.containsKey(first.asLong()));
        assertTrue(retained.containsKey(second.asLong()));
        entities.stateChanged(second, shulker, stone);
        assertFalse(retained.containsKey(second.asLong()));
        entities.clear();
        assertNull(field.get(entities));
    }

    @Test
    void clearingTheLevelForgetsTheLastTicksShulkerShoves() {
        var entities = new CompensatedBlockEntities(InteractionRegistries::defaults);
        int shulker =
                DataTables.defaults().registry().block("minecraft:shulker_box").defaultState();
        entities.blockEvent(new BlockPos(3, 64, 3), shulker, 1, 1);
        entities.tick();
        var shoves = new java.util.ArrayList<CompensatedBlockEntities.ShulkerShove>();
        entities.shulkerShoves(false, true, shoves);
        assertFalse(shoves.isEmpty());
        entities.clear();
        shoves.clear();
        entities.shulkerShoves(false, true, shoves);
        assertTrue(shoves.isEmpty());
    }

    @Test
    void settledAnimationsResumeAfterEveryWritePath() {
        var entities = new CompensatedBlockEntities(InteractionRegistries::defaults);
        var registry = DataTables.defaults().registry();
        int shulker = registry.block("minecraft:shulker_box").defaultState();
        int stone = registry.block("minecraft:stone").defaultState();
        var pos = new BlockPos(5, 70, -9);
        var other = new BlockPos(-40, 12, 3);
        entities.blockEvent(other, shulker, 1, 1);
        var expected = entities.snapshot(pos, shulker);
        for (int tick = 0; tick < 120; tick++) {
            switch (tick) {
                case 3, 60 -> {
                    entities.blockEvent(pos, shulker, 1, 1);
                    expected = ShulkerAnimation.event(expected, 1, 1);
                }
                case 30, 75 -> {
                    entities.blockEvent(pos, shulker, 1, 0);
                    expected = ShulkerAnimation.event(expected, 1, 0);
                }
                case 90 -> {
                    entities.forgetChunk(pos.getX() >> 4, pos.getZ() >> 4);
                    expected = entities.snapshot(pos, shulker);
                    entities.blockEvent(pos, shulker, 1, 1);
                    expected = ShulkerAnimation.event(expected, 1, 1);
                }
                case 100 -> {
                    entities.stateChanged(pos, shulker, stone);
                    entities.stateChanged(pos, stone, shulker);
                    expected = entities.snapshot(pos, shulker);
                    entities.blockEvent(pos, shulker, 1, 1);
                    expected = ShulkerAnimation.event(expected, 1, 1);
                }
                default -> {}
            }
            entities.tick();
            expected = ShulkerAnimation.tick(expected);
            var actual = entities.snapshot(pos, shulker);
            assertEquals(expected.data().keys(), actual.data().keys(), "tick " + tick);
            for (String key : expected.data().keys())
                assertTrue(expected.data().sameValue(key, actual.data()), "tick " + tick + " " + key);
        }
    }

    @Test
    void tickingDrainsOnRemovalWithoutATick() throws Exception {
        var entities = new CompensatedBlockEntities(InteractionRegistries::defaults);
        var registry = DataTables.defaults().registry();
        int shulker = registry.block("minecraft:shulker_box").defaultState();
        int stone = registry.block("minecraft:stone").defaultState();
        var field = CompensatedBlockEntities.class.getDeclaredField("ticking");
        field.setAccessible(true);
        for (int i = 0; i < 64; i++) entities.blockEvent(new BlockPos(i, 64, -i), shulker, 1, 1);
        var ticking = (LongOpenHashSet) field.get(entities);
        assertEquals(64, ticking.size());
        for (int i = 0; i < 64; i += 2) entities.stateChanged(new BlockPos(i, 64, -i), shulker, stone);
        for (int i = 1; i < 64; i += 2) entities.forgetChunk(i >> 4, -i >> 4);
        assertTrue(ticking.isEmpty());
        entities.blockEvent(new BlockPos(3, 64, 3), shulker, 1, 1);
        entities.predict(new BlockPos(3, 64, 3), stone, null);
        assertTrue(ticking.isEmpty());
    }

    @Test
    void tickOutputsMatchTickingEveryEntryAcrossRemovals() {
        var entities = new CompensatedBlockEntities(InteractionRegistries::defaults);
        var registry = DataTables.defaults().registry();
        var shulkerBlock = registry.block("minecraft:shulker_box");
        int shulker = shulkerBlock.defaultState();
        int stone = registry.block("minecraft:stone").defaultState();
        var positions = new BlockPos[16];
        for (int i = 0; i < positions.length; i++) positions[i] = new BlockPos((i % 4) * 9 - 13, 70, (i / 4) * 7 - 11);
        Map<BlockPos, BlockEntityData> reference = new HashMap<>();
        var random = new Random(0x5eedL);
        for (int step = 0; step < 4000; step++) {
            var pos = positions[random.nextInt(positions.length)];
            switch (random.nextInt(8)) {
                case 0, 1, 2 -> {
                    int parameter = random.nextInt(2);
                    entities.blockEvent(pos, shulker, 1, parameter);
                    var previous = reference.get(pos);
                    if (previous == null) previous = BlockEntityPrototypes.create(shulkerBlock, shulker);
                    reference.put(pos, ShulkerAnimation.event(previous, 1, parameter));
                }
                case 3 -> {
                    if (reference.containsKey(pos)) {
                        entities.stateChanged(pos, shulker, stone);
                        reference.remove(pos);
                    }
                }
                case 4 -> {
                    int chunkX = pos.getX() >> 4, chunkZ = pos.getZ() >> 4;
                    entities.forgetChunk(chunkX, chunkZ);
                    reference.keySet().removeIf(p -> p.getX() >> 4 == chunkX && p.getZ() >> 4 == chunkZ);
                }
                default -> {
                    entities.tick();
                    reference.replaceAll((p, data) -> ShulkerAnimation.tick(data));
                }
            }
            for (var entry : reference.entrySet()) {
                var expected = entry.getValue();
                var actual = entities.snapshot(entry.getKey(), shulker);
                assertEquals(expected.data().keys(), actual.data().keys(), "step " + step);
                for (String key : expected.data().keys())
                    assertTrue(expected.data().sameValue(key, actual.data()), "step " + step + " " + key);
            }
        }
    }
}
