package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Column lookups, reintern scans, block-entity cleanup and idle ticks answer exactly as the full scans do. */
class WorldBookkeepingTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void columnLookupsMatchTheMapAcrossEveryWrite() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension(OVERWORLD);
            world.setLastClientboundDimension(OVERWORLD, dimension.dimension());
            world.setDimension(OVERWORLD, dimension);
            var chunks = chunks(world);
            List<String> unloadedSeen = new ArrayList<>();
            world.terrainListener(new CompensatedWorld.TerrainListener() {
                @Override
                public void columnLoaded(int chunkX, int chunkZ) {
                    assertSame(chunks.get(CompensatedWorld.chunkPositionToLong(chunkX, chunkZ)),
                            world.getChunk(chunkX, chunkZ));
                }

                @Override
                public void columnUnloaded(int chunkX, int chunkZ) {
                    assertNull(world.getChunk(chunkX, chunkZ));
                    unloadedSeen.add(chunkX + "," + chunkZ);
                }

                @Override
                public void blockChanged(int x, int y, int z, int state) {}
            });
            var random = new Random(0x5EED);
            int sections = world.getLastClientboundSectionCount();
            for (int step = 0; step < 6000; step++) {
                int x = random.nextInt(5) - 2, z = random.nextInt(5) - 2;
                // Queries first, so the memo holds the column the write changes.
                for (int query = 0; query < 3; query++) {
                    int qx = query == 0 ? x : random.nextInt(5) - 2, qz = query == 0 ? z : random.nextInt(5) - 2;
                    assertColumn(world, chunks, qx, qz);
                    assertColumn(world, chunks, qx, qz);
                }
                int transaction = player.lastTransactionReceived.get() + 1;
                switch (random.nextInt(9)) {
                    case 0 -> world.ensureValidationChunkLoaded(x, z);
                    case 1 -> world.putChunk(x, z, new CachedChunk(new CachedSection[sections], transaction));
                    case 2, 3, 4 -> {
                        String level = random.nextInt(4) == 0 ? NETHER : OVERWORLD;
                        world.addToCache(
                                new CachedChunk(new CachedSection[sections], transaction), level, transaction, x, z,
                                List.of());
                        assertColumn(world, chunks, x, z);
                        player.latencyUtils.handleNettySyncTransaction(transaction);
                    }
                    case 5, 6 -> {
                        world.removeChunkLater(OVERWORLD, x, z, transaction);
                        assertColumn(world, chunks, x, z);
                        player.latencyUtils.handleNettySyncTransaction(transaction);
                    }
                    case 7 -> {
                        if (random.nextInt(20) == 0) world.clearChunksForDimension(
                                random.nextBoolean() ? OVERWORLD : NETHER);
                    }
                    default -> {
                        if (random.nextInt(40) == 0) world.clearChunks();
                    }
                }
                assertColumn(world, chunks, x, z);
            }
            assertFalse(unloadedSeen.isEmpty());
        }
    }

    @Test
    void aRemovedOrReplacedColumnIsNeverAnswered() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = player.compensatedWorld;
            world.ensureValidationChunkLoaded(3, 4);
            var first = world.getChunk(3, 4);
            assertNotNull(first);
            assertSame(first, world.getChunk(3, 4));
            int transaction = player.lastTransactionReceived.get() + 1;
            var replacement = new CachedChunk(new CachedSection[world.getLastClientboundSectionCount()], transaction);
            world.addToCache(replacement, world.getVisibleDimension(), transaction, 3, 4, List.of());
            assertSame(first, world.getChunk(3, 4));
            player.latencyUtils.handleNettySyncTransaction(transaction);
            assertSame(replacement, world.getChunk(3, 4));
            world.removeChunkLater(world.getVisibleDimension(), 3, 4, transaction + 1);
            assertTrue(world.isChunkLoaded(3, 4));
            player.latencyUtils.handleNettySyncTransaction(transaction + 1);
            assertNull(world.getChunk(3, 4));
            assertFalse(world.isChunkLoaded(3, 4));
            world.putChunk(3, 4, null);
            assertNull(world.getChunk(3, 4));
            assertTrue(world.isChunkLoaded(3, 4));
            world.putChunk(3, 4, first);
            assertSame(first, world.getChunk(3, 4));
            world.clearChunks();
            assertFalse(world.isChunkLoaded(3, 4));
            assertNull(world.getChunk(3, 4));
        }
    }

    @Test
    void anOffThreadReadLeavesTheOwnersColumnLookupAlone() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = player.compensatedWorld;
            var gated = new GatedChunks();
            gated.putAll(chunks(world));
            setField(world, "chunks", gated);
            setField(world, "chunkPositions", LongSets.unmodifiable(gated.keySet()));
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(5, 5);
            assertNotNull(world.getChunk(5, 5));
            var packetWorld = player.getPacketWorld();
            // The reader pauses inside its map read while the owner unloads the column it read.
            var reader = new Thread(() -> {
                packetWorld.getBlockStateId(0, world.getMinY(), 0);
                packetWorld.isChunkLoaded(0, 0);
            });
            gated.reader = reader;
            reader.start();
            assertTrue(gated.arrived.await(10, TimeUnit.SECONDS));
            int transaction = player.lastTransactionReceived.get() + 1;
            world.removeChunkLater(world.getVisibleDimension(), 0, 0, transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
            gated.resume.countDown();
            reader.join(10_000);
            assertFalse(reader.isAlive());
            assertNull(world.getChunk(0, 0));
            assertFalse(world.isChunkLoaded(0, 0));
            assertNotNull(world.getChunk(5, 5));
        }
    }

    /** A chunk map whose reads by one thread stop after the lookup until the test lets them return. */
    private static final class GatedChunks extends Long2ObjectOpenHashMap<CachedChunk> {
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        volatile Thread reader;

        @Override
        public CachedChunk get(long key) {
            CachedChunk value = super.get(key);
            if (Thread.currentThread() == reader && arrived.getCount() > 0) {
                arrived.countDown();
                try {
                    resume.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return value;
        }
    }

    private static void assertColumn(CompensatedWorld world, Long2ObjectMap<CachedChunk> chunks, int x, int z) {
        long position = CompensatedWorld.chunkPositionToLong(x, z);
        assertSame(chunks.get(position), world.getChunk(x, z), x + "," + z);
        assertEquals(chunks.containsKey(position), world.isChunkLoaded(x, z), x + "," + z);
    }

    @SuppressWarnings("unchecked")
    static Long2ObjectMap<CachedChunk> chunks(CompensatedWorld world) throws Exception {
        return (Long2ObjectMap<CachedChunk>) field(world, "chunks");
    }

    static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
