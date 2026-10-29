package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JavaPistonShoveTest {
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_RIGHT_SHIFT =
            new ServerboundPlayerInput(true, false, false, true, false, true, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();

    /**
     * A crouching 1.21.4 client against a west-facing piston that fires, then an up-facing piston under the floor
     * beside it. One Level#tickBlockEntities pass after LocalPlayer#sendPosition runs both: the west head's second
     * PistonMovingBlockEntity#moveCollidedEntities shoves the player over the up piston, whose first pass lifts it
     * 0.51. The next travel falls onto the up head and runs into the west head's face, so Entity#collide steps from
     * the lifted box onto the west head's top, 0.49 above it.
     */
    @Test
    void aStepAfterAPistonLiftRisesFromTheLiftedBox() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            BlockPos west = new BlockPos(1, FLOOR_Y, 0);
            BlockPos up = new BlockPos(-1, FLOOR_Y - 1, 0);
            int westPiston = piston("west");
            int upPiston = piston("up");
            f.player.compensatedWorld.updateBlock(west, westPiston);
            f.player.compensatedWorld.updateBlock(up, upPiston);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 against = recorded(205.69999998807907, 62.0, 230.59313981649407);
            f.place(against, new Vec3(0, -0.0784000015258789, 0));
            f.player.xRot = -144.21765F;
            f.player.yRot = 11.13749F;
            f.input(SHIFT);
            assertExact(f.tick(against, true));
            assertExact(f.tick(against, true));

            JavaPistonSlimeLaunchTest.blockEvent(f, west, 0, Direction.WEST.get3DDataValue(), westPiston);
            assertExact(f.tick(against, true));
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.playerPosition(recorded(205.18999998807908, 62.0, 230.59313981649407), Vec3.ZERO, Set.of());
            JavaPistonSlimeLaunchTest.blockEvent(f, up, 0, Direction.UP.get3DDataValue(), upPiston);

            f.input(FORWARD_RIGHT_SHIFT);
            f.player.xRot = -144.21765F;
            f.player.yRot = 11.13749F;
            assertExact(f.tick(recorded(205.1982081881529, 62.0, 230.59180780097327), false));
            f.player.xRot = -144.44263F;
            f.player.yRot = 11.08124F;
            assertExact(f.tick(recorded(204.70567248162507, 63.0, 230.59044431697276), true));
        }
    }

    private static int piston(String facing) {
        return REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", facing);
    }

    /** A position from the #140 capture, moved so the capture's floor top 62.0 is FLOOR_Y. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 205.0, y - 62.0 + FLOOR_Y, z - 230.0);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
