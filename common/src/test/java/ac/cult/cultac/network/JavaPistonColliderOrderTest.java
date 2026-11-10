package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Two east-facing pistons extend in one tick, the one at z -1 first, so its PistonMovingBlockEntity ticks first in
 * Level#tickBlockEntities. Its stone shoves a boat that reaches into the player's row east, out of the path of the
 * second piston's stone, which then shoves the player. The player's Entity#move(PISTON) collides with the boat where
 * the first ticker left it, so neither stroke meets the boat.
 */
class JavaPistonColliderOrderTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final BlockPos FIRST = new BlockPos(-1, FLOOR_Y, -1);
    private static final BlockPos SECOND = new BlockPos(-2, FLOOR_Y, 0);
    private static final int BOAT = 100;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void aBoatAnEarlierPistonShovedNoLongerBlocksTheLaterShove(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var world = f.player.compensatedWorld;
            int piston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
            for (BlockPos base : new BlockPos[] {FIRST, SECOND}) {
                world.updateBlock(base, piston);
                world.updateBlock(base.relative(Direction.EAST), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff();
            // The boat's west face is 0.25 east of the player, beyond AbstractBoat's 0.2 push reach, but inside the
            // 0.31 the second piston's first stroke shoves the player; it spans both rows.
            double boatWest = 0.5 + HALF_WIDTH + 0.25;
            f.player.compensatedEntities.addEntity(
                    BOAT, EntityTypeIds.OAK_BOAT, new Vec3(boatWest + 1.375 / 2, FLOOR_Y, 0.1), 0, 0, 0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            assertExact(f.tick(position, true));

            JavaPistonSlimeLaunchTest.blockEvent(f, FIRST, 0, Direction.EAST.get3DDataValue(), piston);
            JavaPistonSlimeLaunchTest.blockEvent(f, SECOND, 0, Direction.EAST.get3DDataValue(), piston);
            // First strokes: the boat moves to x 1.5 + 0.01, the player's west face from x 0.2 to 0.5 + 0.01.
            Vec3 first = new Vec3(0.5 + 0.01 + HALF_WIDTH, FLOOR_Y, 0.5);
            // Second strokes: the faces reach x 2 and x 1; each shove clears its face by 0.01.
            Vec3 second = new Vec3(1.0 + 0.01 + HALF_WIDTH, FLOOR_Y, 0.5);
            if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) {
                // LocalPlayer#sendChanges reports after each pass; a horizontal Entity#move(PISTON) clears onGround.
                assertExact(f.tick(first, false));
                assertExact(f.tick(second, false));
            } else {
                assertExact(f.tick(position, true));
                assertExact(f.tick(first, true));
                assertExact(f.tick(second, true));
            }
            assertEquals(
                    2.0 + 0.01 + 1.375 / 2,
                    f.player.compensatedEntities.getEntity(BOAT).clientPhysicalPosition.x,
                    1.0E-6,
                    "both strokes of the first piston shove the boat");
        }
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
