package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * An east-facing piston in chunk -1 pushes stone into a player standing in chunk 0. ClientChunkCache#updateViewCenter
 * only moves the lookup range, and LevelChunk$BoundTickingBlockEntity#tick reads its own chunk's state, so the hidden
 * PistonMovingBlockEntity keeps ticking and its second stroke still shoves the player. Only ClientLevel#unload (or a
 * block change at the position) removes it.
 */
class JavaPistonChunkCacheCenterTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double FALL = 0.0784000015258789;
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final BlockPos PISTON = new BlockPos(-3, FLOOR_Y, 0);
    private static final BlockPos MOVING = new BlockPos(-1, FLOOR_Y, 0);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aViewCenterThatHidesAMovingPistonKeepsItsTickerAndShove(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var world = f.player.compensatedWorld;
            // Radius 2 stores chunks within 5 of the center.
            world.initializeClientChunkCache(2);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.ensureValidationChunkLoaded(x, z);
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
            world.updateBlock(PISTON, piston);
            world.updateBlock(new BlockPos(-2, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff();

            // The second stroke's area reaches x = 0, past the player's west face: it moves that depth plus 0.01.
            Vec3 position = new Vec3(0.25, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            assertExact(f.tick(position, true));

            JavaPistonSlimeLaunchTest.blockEvent(f, PISTON, 0, Direction.EAST.get3DDataValue(), piston);
            // Chunk 0 stays in view while chunk -1, holding the moving stone, leaves it.
            world.clientChunkCacheCenter(5, 0);
            assertEquals(0, world.getBlockStateIdAt(MOVING), "the hidden chunk reads as air");

            Vec3 pushed = position.add(-(position.x - HALF_WIDTH) + 0.01, 0, 0);
            if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) {
                // LocalPlayer#sendChanges reports after each pass; the horizontal Entity#move(PISTON) clears onGround.
                assertExact(f.tick(position, true));
                assertExact(f.tick(pushed, false));
            } else {
                // LocalPlayer#tick reports before the pass: the next report carries the shove.
                assertExact(f.tick(position, true));
                assertExact(f.tick(position, true));
                assertExact(f.tick(pushed, true));
            }

            world.clientChunkCacheCenter(0, 0);
            assertTrue(
                    BlockIds.is(world.getBlockStateIdAt(MOVING), BlockIds.MOVING_PISTON),
                    "the moving block is visible again");
            // Five death ticks later PistonMovingBlockEntity#tick settles the stone where the stroke left it.
            for (int tick = 0; tick < 8; tick++) world.removeInvalidPistonLikeStuff();
            assertTrue(BlockIds.is(world.getBlockStateIdAt(MOVING), BlockIds.STONE));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_3"})
    void unloadingTheHiddenChunkStillRemovesItsMovingPiston(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var world = f.player.compensatedWorld;
            world.initializeClientChunkCache(2);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.ensureValidationChunkLoaded(x, z);
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
            world.updateBlock(PISTON, piston);
            world.updateBlock(new BlockPos(-2, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff();

            JavaPistonSlimeLaunchTest.blockEvent(f, PISTON, 0, Direction.EAST.get3DDataValue(), piston);
            world.clientChunkCacheCenter(5, 0);
            world.removeInvalidPistonLikeStuff();
            assertEquals(2, pistons(world.nextBlockEntityPass()), "the hidden stone and head still tick");

            world.clientChunkCacheCenter(0, 0);
            f.player.sendTransaction();
            world.removeChunkLater(-1, 0, f.player.lastTransactionSent.get());
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(0, pistons(world.nextBlockEntityPass()), "ClientLevel#unload clears the chunk's tickers");
        }
    }

    private static int pistons(ClientBlockEntityPass pass) {
        int count = 0;
        for (var mover : pass.movers()) if (mover instanceof ClientBlockEntityPass.Piston) count++;
        return count;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
