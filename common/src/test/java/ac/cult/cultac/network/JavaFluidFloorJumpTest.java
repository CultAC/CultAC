package ac.cult.cultac.network;

import static ac.cult.cultac.network.FluidPools.JUMP;
import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LivingEntity#aiStep runs jumpFromGround on a fluid floor only while the fluid stands at or below
 * Entity#getFluidJumpThreshold; deeper, the jump is jumpInLiquid.
 */
class JavaFluidFloorJumpTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double GROUND_JUMP = 0.41999998688697815;

    @Test
    void aDeepWaterFloorTakesOnlyTheLiquidJump() throws Exception {
        try (var f = FluidPools.pool(BlockIds.WATER, FLOOR_Y + 8)) {
            assertFalse(exact(jump(f, FLOOR_Y, GROUND_JUMP)));
        }
        liquidJump(BlockIds.WATER);
    }

    @Test
    void aDeepLavaFloorTakesOnlyTheLiquidJump() throws Exception {
        try (var f = FluidPools.pool(BlockIds.LAVA, FLOOR_Y + 8)) {
            assertFalse(exact(jump(f, FLOOR_Y, GROUND_JUMP)));
        }
        liquidJump(BlockIds.LAVA);
    }

    @Test
    void aShallowWaterFloorTakesTheGroundJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            layer(f, FLOOR_Y, REGISTRY.with(BlockIds.WATER.defaultState(), "level", "7"));
            PredictionResult result = jump(f, FLOOR_Y, GROUND_JUMP);
            assertTrue(exact(result), FluidPools.report(result));
        }
    }

    @Test
    void aShallowLavaFloorTakesTheGroundJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            layer(f, FLOOR_Y, REGISTRY.with(BlockIds.LAVA.defaultState(), "level", "6"));
            PredictionResult result = jump(f, FLOOR_Y, GROUND_JUMP);
            assertTrue(exact(result), FluidPools.report(result));
        }
    }

    @Test
    void aWaterloggedSlabFloorTakesTheGroundJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            int slab = REGISTRY.with(
                    REGISTRY.with(REGISTRY.block("minecraft:stone_slab").defaultState(), "type", "bottom"),
                    "waterlogged",
                    "true");
            layer(f, FLOOR_Y, slab);
            PredictionResult result = jump(f, FLOOR_Y + 0.5, GROUND_JUMP);
            assertTrue(exact(result), FluidPools.report(result));
        }
    }

    @Test
    void aSmallPlayerInShallowWaterTakesOnlyTheLiquidJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            scale(f, 0.2F);
            PredictionResult dry = jump(f, FLOOR_Y, GROUND_JUMP);
            assertTrue(exact(dry), FluidPools.report(dry));
        }
        try (var f = new JavaTickFixture()) {
            layer(f, FLOOR_Y, REGISTRY.with(BlockIds.WATER.defaultState(), "level", "7"));
            scale(f, 0.2F);
            assertFalse(exact(jump(f, FLOOR_Y, GROUND_JUMP)));
        }
    }

    /** A still tick standing at {@code y}, then the first tick holding jump, rising by {@code rise}. */
    private static PredictionResult jump(JavaTickFixture f, double y, double rise) {
        FluidPools.settle(f);
        Vec3 start = new Vec3(0.5, y, 0.5);
        f.place(start, new Vec3(0.0, -0.005, 0.0));
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        PredictionResult still = f.tick(start, true);
        assertTrue(exact(still), "standing: " + FluidPools.report(still));
        f.input(JUMP);
        return f.tick(start.add(0.0, rise, 0.0), false);
    }

    /** Eight ticks of LivingEntity#jumpInLiquid's 0.04 from a deep floor of {@code fluid} are exact. */
    private static void liquidJump(BlockDefinition fluid) throws Exception {
        boolean water = fluid == BlockIds.WATER;
        try (var f = FluidPools.pool(fluid, FLOOR_Y + 8)) {
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            assertTrue(exact(f.tick(start, true)), "standing");
            f.input(JUMP);
            double y = FLOOR_Y;
            double v = water ? -0.005 : -0.02;
            for (int tick = 0; tick < 8; tick++) {
                double delta = (Math.abs(v) < 0.003 ? 0.0 : v) + 0.04;
                y += delta;
                PredictionResult result = f.tick(new Vec3(0.5, y, 0.5), false);
                assertTrue(exact(result), "tick " + tick + ": " + FluidPools.report(result));
                v = water ? delta * 0.8 - 0.005 : delta * 0.5 - 0.02;
            }
        }
    }

    private static void scale(JavaTickFixture f, float scale) {
        f.player.compensatedEntities.getSelf().scale = scale;
        f.player.dimensionScale = scale;
    }

    private static void layer(JavaTickFixture f, int y, int state) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
            }
        }
    }
}
