package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A scan whose box reaches past the stored columns visits the cells, in the order, of a walk over every column of the
 * box, and its length follows the stored columns.
 */
class CollisionsColumnClipScanTest {
    private static final int STONE = BlockIds.STONE.defaultState();
    private static final int HONEY = BlockIds.HONEY_BLOCK.defaultState();

    private record Cell(int state, int x, int y, int z) {}

    /** Stored columns span chunk X -5..5 and chunk Z -2..7. */
    private static JavaTickFixture fixture(Random random) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
        CompensatedWorld world = f.player.compensatedWorld;
        int minZ = -2, maxZ = 7;
        for (int x = -5; x <= 5; x++) for (int z = minZ; z <= maxZ; z++) world.ensureValidationChunkLoaded(x, z);
        for (int i = 0; i < 6000; i++)
            world.updateBlock(
                    new BlockPos(
                            random.nextInt(176) - 88,
                            FLOOR_Y + random.nextInt(6),
                            random.nextInt((maxZ - minZ + 1) * 16) + minZ * 16),
                    random.nextInt(3) == 0 ? HONEY : STONE);
        return f;
    }

    /** Every column of the box in scan order, read through the public world view. */
    private static List<Object> everyColumn(CultPlayer player, SimpleCollisionBox box, int stopAt) {
        CompensatedWorld world = player.compensatedWorld;
        int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX);
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ);
        int minSection = world.getMinHeight() >> 4;
        int minY = Math.max(minSection << 4, (int) Math.floor(box.minY));
        int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(box.maxY));
        List<Object> seen = new ArrayList<>();
        for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++)
            for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
                CachedChunk chunk = world.getChunk(chunkX, chunkZ);
                if (chunk == null) continue;
                for (int y = minY; y <= maxY; y++) {
                    CachedSection section = chunk.getSection((y >> 4) - minSection);
                    if (section == null) continue;
                    for (int z = Math.max(minZ, chunkZ << 4); z <= Math.min(maxZ, (chunkZ << 4) + 15); z++)
                        for (int x = Math.max(minX, chunkX << 4); x <= Math.min(maxX, (chunkX << 4) + 15); x++) {
                            int state = section.getStateId(CachedChunk.index(x & 15, y & 15, z & 15));
                            if (!BlockIds.is(state, BlockIds.HONEY_BLOCK)) continue;
                            seen.add(new Cell(state, x, y, z));
                            if (seen.size() == stopAt) {
                                seen.add(true);
                                return seen;
                            }
                        }
                }
            }
        seen.add(false);
        return seen;
    }

    private static List<Object> honey(CultPlayer player, SimpleCollisionBox box, int stopAt) {
        List<Object> seen = new ArrayList<>();
        boolean result = Collisions.hasState(player, box, state -> BlockIds.is(state, BlockIds.HONEY_BLOCK), (state, pos) -> {
            seen.add(new Cell(state, pos.getX(), pos.getY(), pos.getZ()));
            return seen.size() == stopAt;
        });
        seen.add(result);
        return seen;
    }

    private static List<Object> positioned(CultPlayer player, SimpleCollisionBox box, int stopAt) {
        List<Object> seen = new ArrayList<>();
        boolean result = Collisions.hasState(player, box, (state, x, y, z) -> {
            if (!BlockIds.is(state, BlockIds.HONEY_BLOCK)) return false;
            seen.add(new Cell(state, x, y, z));
            return seen.size() == stopAt;
        });
        seen.add(result);
        return seen;
    }

    @Test
    void boxesPastTheStoredColumnsMatchAWalkOfEveryColumn() throws Exception {
        Random random = new Random(14);
        try (var f = fixture(random)) {
            for (int i = 0; i < 600; i++) {
                double span = random.nextBoolean() ? 40 : 2500;
                double x = random.nextDouble() * 260 - 130 - random.nextDouble() * span;
                double z = random.nextDouble() * 260 - 130 - random.nextDouble() * span;
                var box = new SimpleCollisionBox(
                        x, FLOOR_Y, z, x + random.nextDouble() * span, FLOOR_Y + random.nextDouble() * 6,
                        z + random.nextDouble() * span);
                int stopAt = random.nextInt(40) - 1;
                List<Object> expected = everyColumn(f.player, box, stopAt);
                assertEquals(expected, honey(f.player, box, stopAt), box.toString());
                assertEquals(expected, positioned(f.player, box, stopAt), box.toString());
            }
        }
    }

    @Test
    void aBoxAcrossTheWorldVisitsTheStoredColumnsAndReturns() throws Exception {
        try (var f = fixture(new Random(12))) {
            var stored = new SimpleCollisionBox(-200, FLOOR_Y, -200, 200, FLOOR_Y + 5.5, 200);
            List<Object> expected = everyColumn(f.player, stored, -1);
            assertFalse(expected.size() < 100, "the stored columns hold honey");
            var world = new SimpleCollisionBox(-2.9e7, FLOOR_Y, -2.9e7, 2.9e7, FLOOR_Y + 5.5, 2.9e7);
            assertEquals(expected, assertTimeoutPreemptively(Duration.ofSeconds(20), () -> honey(f.player, world, -1)));
            var move = new SimpleCollisionBox(-200, FLOOR_Y, -200, 2.9e7, FLOOR_Y + 5.5, 2.9e7);
            assertEquals(
                    expected,
                    assertTimeoutPreemptively(Duration.ofSeconds(20), () -> honey(f.player, move, -1)));
            var far = new SimpleCollisionBox(1.0e7, FLOOR_Y, 1.0e7, 2.9e7, FLOOR_Y + 5.5, 2.9e7);
            assertEquals(
                    List.of(false), assertTimeoutPreemptively(Duration.ofSeconds(20), () -> honey(f.player, far, -1)));
        }
    }

    @Test
    void collidersOfABoxPastTheStoredColumnsMatchThoseOfASmallerBoxPastThem() throws Exception {
        try (var f = fixture(new Random(13))) {
            List<String> expected = colliders(f.player, 400);
            assertEquals(expected, colliders(f.player, 3000));
            assertEquals(expected, assertTimeoutPreemptively(Duration.ofSeconds(20), () -> colliders(f.player, 1.0e6)));
        }
    }

    private static List<String> colliders(CultPlayer player, double reach) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        Collisions.getCollisionBoxes(
                player,
                new SimpleCollisionBox(-reach, FLOOR_Y + 1.5, -reach, reach, FLOOR_Y + 3.5, reach),
                boxes,
                false,
                FLOOR_Y + 1.5);
        assertFalse(boxes.isEmpty());
        List<String> out = new ArrayList<>();
        for (SimpleCollisionBox box : boxes)
            out.add(box.minX + " " + box.minY + " " + box.minZ + " " + box.maxX + " " + box.maxY + " " + box.maxZ);
        return out;
    }
}
