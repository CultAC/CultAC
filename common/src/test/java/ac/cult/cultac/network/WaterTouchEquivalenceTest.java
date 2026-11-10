package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The water-contact scan against the per-cell position lookups it replaces, on randomized worlds with fluids. */
class WaterTouchEquivalenceTest {
    private static final int QUERIES = 4000;

    @Test
    void touchesWaterMatchesThePositionLookups() throws Exception {
        int touched = 0, dry = 0;
        for (long seed = 1; seed <= 3; seed++) {
            try (var services = new RecordConsumerServices();
                    var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
                CultPlayer player = fixture.player;
                CollisionGatheringEquivalenceTest.CollisionWorlds.populate(player, seed);
                var random = new SplittableRandom(seed * 17);
                flood(player, random);
                for (int i = 0; i < QUERIES; i++) {
                    double x = random.nextDouble(-20, 36), z = random.nextDouble(-20, 36);
                    double y = random.nextDouble(40, 90);
                    double w = random.nextInt(4) == 0 ? random.nextDouble(0, 3) : random.nextDouble(0.2, 0.8);
                    double h = random.nextDouble(0, 2.5);
                    // Under 0.002 the deflated bounds cross and stay crossed.
                    if (random.nextInt(8) == 0) w = random.nextDouble(0, 0.004);
                    if (random.nextInt(8) == 0) h = random.nextDouble(0, 0.004);
                    if (random.nextInt(4) == 0) y = Math.floor(y) + random.nextInt(10) / 9.0;
                    var box = new SimpleCollisionBox(x - w / 2, y, z - w / 2, x + w / 2, y + h, z + w / 2);
                    long[] before = bits(box);
                    boolean expected = reference(player, box);
                    boolean actual = JavaFallDistance.touchesWater(player, box);
                    assertEquals(expected, actual, "box " + box);
                    assertEquals(Arrays.toString(before), Arrays.toString(bits(box)));
                    if (expected) touched++;
                    else dry++;
                }
            }
        }
        assertTrue(touched > QUERIES / 4 && dry > QUERIES / 4, "touched " + touched + " dry " + dry);
    }

    static void flood(CultPlayer player, SplittableRandom random) {
        var registry = DataTables.defaults().registry();
        var water = registry.block("minecraft:water");
        int seagrass = registry.block("minecraft:seagrass").defaultState();
        for (int x = -20; x < 36; x++)
            for (int z = -20; z < 36; z++)
                for (int y = 44; y < 84; y++) {
                    int roll = random.nextInt(6);
                    if (roll == 0) {
                        player.compensatedWorld.updateBlock(
                                new BlockPos(x, y, z), water.firstState() + random.nextInt(water.stateCount()));
                    } else if (roll == 1 && random.nextInt(4) == 0) {
                        player.compensatedWorld.updateBlock(new BlockPos(x, y, z), seagrass);
                    }
                }
    }

    private static long[] bits(SimpleCollisionBox box) {
        return new long[] {
            Double.doubleToRawLongBits(box.minX), Double.doubleToRawLongBits(box.minY),
            Double.doubleToRawLongBits(box.minZ), Double.doubleToRawLongBits(box.maxX),
            Double.doubleToRawLongBits(box.maxY), Double.doubleToRawLongBits(box.maxZ)
        };
    }

    static boolean reference(CultPlayer player, SimpleCollisionBox entityBox) {
        var box = entityBox.copy().expand(-0.001);
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++)
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++)
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var fluid = ClientFluidQueries.fluidAt(player.compensatedWorld, pos);
                    if (FluidTags.WATER.test(fluid)
                            && pos.getY() + ClientFluidQueries.height(player.compensatedWorld, pos, fluid) >= box.minY)
                        return true;
                }
        return false;
    }
}
