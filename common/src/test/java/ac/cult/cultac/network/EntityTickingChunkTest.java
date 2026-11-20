package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * CompensatedEntities#isTicking answers as a direct chunk lookup at the entity's physical position would, through
 * chunk loads, replacements, unloads, dimension and full clears, validation loads, test puts, position changes and
 * world swaps, each queried repeatedly so remembered answers are reused between changes.
 */
class EntityTickingChunkTest {
    private static final int[] TYPES = {
        EntityTypeIds.ZOMBIE, EntityTypeIds.OAK_BOAT, EntityTypeIds.ARMOR_STAND, EntityTypeIds.HORSE, EntityTypeIds.PIG
    };
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void tickingFollowsTheLoadedChunks() throws Exception {
        for (long seed = 0; seed < 12; seed++) {
            try (var f = new JavaTickFixture()) {
                run(f, seed);
            }
        }
    }

    private static void run(JavaTickFixture f, long seed) {
        CultPlayer player = f.player;
        f.place(new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5), Vec3.ZERO);
        CompensatedEntities entities = player.compensatedEntities;
        CompensatedWorld first = player.compensatedWorld;
        CompensatedWorld second = new CompensatedWorld(player);
        for (int t = 0; t < TYPES.length; t++) {
            entities.addEntity(10 + t, TYPES[t], new Vec3(t * 2 + 0.5, 64, 2.5), 0, 0, 0);
        }
        Random random = new Random(seed);
        int ticking = 0;
        int idle = 0;
        for (int step = 0; step < 3_000; step++) {
            CompensatedWorld world = player.compensatedWorld;
            int chunkX = random.nextInt(7) - 3;
            int chunkZ = random.nextInt(7) - 3;
            int kind = random.nextInt(100);
            if (kind < 18) {
                world.addToCache(column(), random.nextInt(4) == 0 ? NETHER : OVERWORLD, chunkX, chunkZ);
                f.pongThrough(player.lastTransactionSent.get());
            } else if (kind < 32) {
                world.removeChunkLater(chunkX, chunkZ);
                f.pongThrough(player.lastTransactionSent.get());
            } else if (kind < 34) {
                world.clearChunksForDimension(random.nextBoolean() ? NETHER : OVERWORLD);
            } else if (kind < 35) {
                world.clearChunks();
            } else if (kind < 40) {
                world.ensureValidationChunkLoaded(chunkX, chunkZ);
            } else if (kind < 44) {
                world.putChunk(chunkX, chunkZ, column());
            } else if (kind < 70) {
                PacketEntity entity = entities.getEntity(10 + random.nextInt(TYPES.length));
                Vec3 at = entity.clientPhysicalPosition;
                entity.clientPhysicalPosition = switch (random.nextInt(6)) {
                    case 0 -> null;
                    case 1 -> at == null ? null : new Vec3(at.x, at.y, at.z);
                    case 2 -> at == null ? null : new Vec3(at.x + random.nextDouble() - 0.5, at.y, at.z);
                    default -> new Vec3(random.nextDouble() * 112 - 56, 64, random.nextDouble() * 112 - 56);
                };
            } else if (kind < 73) {
                player.compensatedWorld = world == first ? second : first;
            }
            int queries = 1 + random.nextInt(3);
            for (int q = 0; q < queries; q++) {
                for (int id = 10; id < 10 + TYPES.length; id++) {
                    PacketEntity entity = entities.getEntity(id);
                    boolean expected = direct(player.compensatedWorld, entity);
                    assertEquals(expected, entities.isTicking(entity), "seed=" + seed + " step=" + step + " id=" + id);
                    if (expected) ticking++;
                    else idle++;
                }
            }
            assertTrue(entities.isTicking(entities.getEntity(player.entityID)));
        }
        assertTrue(ticking > 1_000 && idle > 1_000, "seed=" + seed + ": " + ticking + "/" + idle);
    }

    private static boolean direct(CompensatedWorld world, PacketEntity entity) {
        Vec3 position = entity.clientPhysicalPosition;
        return position != null
                && world.isChunkLoaded(CultMath.floor(position.x) >> 4, CultMath.floor(position.z) >> 4);
    }

    private static CompensatedWorld.CachedChunk column() {
        return new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0);
    }
}
