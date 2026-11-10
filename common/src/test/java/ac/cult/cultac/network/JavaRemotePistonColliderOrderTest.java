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
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Two tracked boats and two pistons that extend in one tick. The south-facing piston's PistonMovingBlockEntity ticks
 * first and shoves boat Y south into the path of boat X; the east-facing one then shoves X east into Y. Each move
 * collides with the boats where the earlier moves of the tick left them, whichever boat the model lists first: Y
 * clips X, which keeps only the 0.175 left before Y's west face of its 0.46 shove.
 */
class JavaRemotePistonColliderOrderTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double HALF = 1.375 / 2;
    private static final BlockPos SOUTH_PISTON = new BlockPos(2, FLOOR_Y, -4);
    private static final BlockPos EAST_PISTON = new BlockPos(-2, FLOOR_Y, 0);

    @ParameterizedTest
    @CsvSource({"100, 101", "101, 100"})
    void aBoatAnEarlierPistonMovedClipsTheLaterShove(int x, int y) throws Exception {
        try (var f = new JavaTickFixture()) {
            var world = f.player.compensatedWorld;
            int south = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "south");
            int east = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
            world.updateBlock(SOUTH_PISTON, south);
            world.updateBlock(SOUTH_PISTON.relative(Direction.SOUTH), BlockIds.STONE.defaultState());
            world.updateBlock(EAST_PISTON, east);
            world.updateBlock(EAST_PISTON.relative(Direction.EAST), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff();
            var entities = f.player.compensatedEntities;
            // X's west face is 0.05 inside the east stone's first stroke; Y lies just north of X's row, ahead of it.
            entities.addEntity(x, EntityTypeIds.OAK_BOAT, new Vec3(0.05 + HALF, FLOOR_Y, 0.5), 0, 0, 0);
            entities.addEntity(y, EntityTypeIds.OAK_BOAT, new Vec3(1.6 + HALF, FLOOR_Y, -0.25 - HALF), 0, 0, 0);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 6.5);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            assertExact(f.tick(position, true));

            JavaPistonSlimeLaunchTest.blockEvent(f, SOUTH_PISTON, 0, Direction.SOUTH.get3DDataValue(), south);
            JavaPistonSlimeLaunchTest.blockEvent(f, EAST_PISTON, 0, Direction.EAST.get3DDataValue(), east);
            assertExact(f.tick(position, true));
            // The south stone's face reaches z -1.5: Y's north face moves 0.125 + 0.01 into X's row.
            assertEquals(-0.25 - HALF + 0.135, entities.getEntity(y).clientPhysicalPosition.z, 1.0E-6);
            assertEquals(1.6 - HALF, entities.getEntity(x).clientPhysicalPosition.x, 1.0E-6, "Y clips X's shove");
        }
    }

    private static void assertExact(ac.cult.cultac.checks.impl.prediction.PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
