package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ClientChunkCache's range/storage and the section manager's ticking chunks are independent vanilla state. */
class JavaEntityChunkCacheOrderTest {
    @Test
    void anOutsideRangeForgetDoesNotUnloadARetainedEntity() throws Exception {
        try (var f = new JavaTickFixture()) {
            var world = initialize(f);
            world.putChunk(0, 1, column());
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(.5, 80, 20.5), 0, 0, 0);
            long order = f.player.compensatedEntities.getEntity(100).getClientTickOrder();
            world.clientChunkCacheCenter(20, 20);
            assertFalse(world.isChunkLoaded(0, 1));
            assertEquals(List.of(100), ticks(f));
            world.removeChunkLater(0, 1, 0);
            assertEquals(List.of(100), ticks(f));
            assertEquals(order, f.player.compensatedEntities.getEntity(100).getClientTickOrder());
            world.clientChunkCacheCenter(0, 0);
            assertTrue(world.isChunkLoaded(0, 1));
        }
    }

    @Test
    void replacingACircularSlotStopsItsOldChunkAndReloadAppendsTheEntity() throws Exception {
        try (var f = new JavaTickFixture()) {
            var world = initialize(f);
            world.putChunk(0, 1, column());
            var entities = f.player.compensatedEntities;
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(.5, 80, 20.5), 0, 0, 0);
            // Radius 2 means storage radius 5, width 11: these two chunks share one circular slot.
            world.clientChunkCacheCenter(11, 0);
            world.putChunk(11, 1, column());
            assertEquals(List.of(), ticks(f));
            world.putChunk(11, 2, column());
            entities.addEntity(101, EntityTypeIds.OAK_BOAT, new Vec3(176.5, 80, 40.5), 0, 0, 0);
            assertEquals(List.of(101), ticks(f));
            world.clientChunkCacheCenter(5, 0);
            world.putChunk(0, 1, column());
            assertEquals(List.of(101, 100), ticks(f));
        }
    }

    @Test
    void shrinkingTheCacheDropsTerrainWithoutStoppingItsEntities() throws Exception {
        try (var f = new JavaTickFixture()) {
            var world = initialize(f);
            world.clientChunkCacheRadius(10);
            world.putChunk(9, 1, column());
            var entities = f.player.compensatedEntities;
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(144.5, 80, 20.5), 0, 0, 0);
            long order = entities.getEntity(100).getClientTickOrder();
            world.clientChunkCacheRadius(2);
            assertFalse(world.isChunkLoaded(9, 1));
            assertEquals(List.of(100), ticks(f));
            world.clientChunkCacheRadius(10);
            assertFalse(world.isChunkLoaded(9, 1), "discarded slots do not reappear when the cache expands");
            world.removeChunkLater(9, 1, 0);
            assertEquals(List.of(100), ticks(f), "a forget for an absent slot calls no unload callback");
            world.putChunk(9, 1, column());
            assertEquals(
                    order, entities.getEntity(100).getClientTickOrder(), "a repeated startTicking keeps its place");
            world.removeChunkLater(9, 1, 0);
            assertEquals(List.of(), ticks(f));
        }
    }

    @Test
    void outsideRangeChunkDataDoesNotStartTickingAnEntity() throws Exception {
        try (var f = new JavaTickFixture()) {
            var world = initialize(f);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(320.5, 80, 20.5), 0, 0, 0);
            world.addToCache(column(), 20, 1);
            assertFalse(world.isChunkLoaded(20, 1));
            assertEquals(List.of(), ticks(f));
            world.clientChunkCacheCenter(20, 1);
            world.addToCache(column(), 20, 1);
            assertTrue(world.isChunkLoaded(20, 1));
            assertEquals(List.of(100), ticks(f));
        }
    }

    private static CompensatedWorld initialize(JavaTickFixture f) {
        var world = f.player.compensatedWorld;
        world.clearChunks();
        world.initializeClientChunkCache(2);
        return world;
    }

    private static CompensatedWorld.CachedChunk column() {
        return new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[24], 0);
    }

    private static List<Integer> ticks(JavaTickFixture f) {
        List<Integer> result = new ArrayList<>();
        f.player.compensatedEntities.tickClientEntities(e -> result.add(e.getEntityId()));
        return result;
    }
}
