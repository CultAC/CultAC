package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldAllocationTest {
    @Test
    void fixedShapeShortcutMatchesTheActualBehaviorForEveryAdmittedState() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var geometry = fixture.player.compensatedWorld.geometry();
            var registry = ac.cult.blocksim.data.DataTables.defaults().registry();
            var point = new ac.cult.blocksim.engine.BlockPos(4, 64, 4);
            var context = new ac.cult.blocksim.engine.EntityCollisionContext(
                    64.1, true, 3, true, false, "minecraft:powder_snow_bucket", true, false, false);
            int admitted = 0;
            for (int state = 0; state < registry.stateCount(); state++) {
                var fixed = geometry.staticCollision(state);
                if (fixed == null) continue;
                admitted++;
                assertSame(geometry.collision(state, point, context), fixed);
                assertSame(geometry.collision(state, (ac.cult.blocksim.engine.BlockPos) null, null), fixed);
            }
            assertTrue(admitted > 0);
            assertNotNull(
                    geometry.staticCollision(registry.block("minecraft:chest").defaultState()));
            assertNotNull(geometry.staticCollision(
                    registry.block("minecraft:oak_stairs").defaultState()));
            for (String dynamic : java.util.List.of(
                    "minecraft:scaffolding",
                    "minecraft:shulker_box",
                    "minecraft:powder_snow",
                    "minecraft:moving_piston",
                    "minecraft:bamboo")) {
                assertNull(geometry.staticCollision(registry.block(dynamic).defaultState()), dynamic);
            }
        }
    }

    @Test
    void primitiveIterationRetainsExactExpirationAndFluidCleanup() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var world = fixture.player.compensatedWorld;
            var collision = (Long2IntOpenHashMap) field(world, "recentClientCollisionChanges");
            var fluid = (Long2IntOpenHashMap) field(world, "recentClientFluidChanges");
            var kinds = (Long2ByteOpenHashMap) field(world, "recentClientFluidChangeKinds");
            for (int i = 0; i < 100; i++) {
                collision.put(i, i % 3 + 1);
                fluid.put(i, i % 3 + 1);
                kinds.put(i, (byte) 3);
            }
            for (int tick = 1; tick <= 3; tick++) {
                invoke(world, "tickRecentClientCollisionChanges");
                invoke(world, "tickRecentClientFluidChanges");
                for (int i = 0; i < 100; i++) {
                    int remaining = i % 3 + 1 - tick;
                    assertEquals(remaining > 0, collision.containsKey(i));
                    assertEquals(remaining > 0, fluid.containsKey(i));
                    assertEquals(remaining > 0, kinds.containsKey(i));
                    if (remaining > 0) {
                        assertEquals(remaining, collision.get(i));
                        assertEquals(remaining, fluid.get(i));
                    }
                }
            }
        }
    }

    @Test
    void everyWriteResetsTheExistingReinternCursorWithoutSkippingCallbacks() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var world = fixture.player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            int stone = ac.cult.blocksim.data.DataTables.defaults()
                    .registry()
                    .block("minecraft:stone")
                    .defaultState();
            world.updateBlock(new BlockPos(4, 64, 4), stone);
            var pending = (Map<?, ?>) field(world, "pendingReinternSections");
            assertEquals(1, pending.size());
            var entry = pending.values().iterator().next();
            var cursor = entry.getClass().getDeclaredField("nextComparisonIndex");
            cursor.setAccessible(true);
            cursor.setInt(entry, 7);
            world.applyBlockChangeRawDANGER(4, 64, 4, stone);
            assertSame(entry, pending.values().iterator().next());
            assertEquals(0, cursor.getInt(entry));
            assertEquals(stone, world.getBlockStateIdAt(4, 64, 4));
        }
    }

    @Test
    void unstablePendingReinternsStayPendingWithoutCollectingCandidates() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var world = fixture.player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            int gold = ac.cult.blocksim.data.DataTables.defaults()
                    .registry()
                    .block("minecraft:gold_block")
                    .defaultState();
            for (int section = 0; section < 4; section++) world.updateBlock(new BlockPos(1, section * 16 + 2, 0), gold);
            var pending = (Map<?, ?>) field(world, "pendingReinternSections");
            assertEquals(4, pending.size());
            var candidates = (List<?>) field(world, "reinternCandidates");
            var states = new ArrayList<>(pending.values());

            for (int tick = 0; tick < 3; tick++) {
                invoke(world, "processPendingReinterns");
                assertEquals(states, new ArrayList<>(pending.values()));
                assertTrue(candidates.isEmpty());
            }
            for (int section = 0; section < 4; section++) {
                assertEquals(gold, world.getBlockStateIdAt(1, section * 16 + 2, 0));
            }
        }
    }

    private static Object field(CompensatedWorld world, String name) throws Exception {
        var field = CompensatedWorld.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(world);
    }

    private static void invoke(CompensatedWorld world, String name) throws Exception {
        var method = CompensatedWorld.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(world);
    }
}
