package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
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
    private static final ServerboundPlayerInput LEFT_SHIFT =
            new ServerboundPlayerInput(false, false, true, false, false, true, true);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, true);
    private static final ServerboundPlayerInput RIGHT =
            new ServerboundPlayerInput(false, false, false, true, false, false, true);
    private static final ServerboundPlayerInput FORWARD_RIGHT =
            new ServerboundPlayerInput(true, false, false, true, false, false, true);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    private static final ServerboundPlayerInput NOKEYS =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double PLAYER_HALF_WIDTH = 0.6F / 2.0F;

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

    /**
     * A 1.21.4 client lands on a west piston's arm as it retracts. The pass after that landing shoves it east onto
     * the base, and the next packet carries that shove. The pass after it misses the player, so the following packet
     * is pure travel: its X movement stays in Entity#deltaMovement and carries into the walk after it.
     */
    @Test
    void aPistonShoveIsReportedInOnePacket() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            BlockPos base = new BlockPos(1, FLOOR_Y, 0);
            int extended = REGISTRY.with(piston("west"), "extended", "true");
            int head = REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", "west");
            f.player.compensatedWorld.updateBlock(base, extended);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), head);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            f.player.xRot = -142.30359F;
            f.player.yRot = 43.59375F;
            f.place(
                    recorded(205.56339260206136, 63.0, 230.7578197490457),
                    new Vec3(
                            (205.56339260206136 - 205.47281451264612) * (0.6F * 0.91F),
                            -0.0784000015258789,
                            (230.7578197490457 - 230.75966202671816) * (0.6F * 0.91F)));
            f.player.onGround = true;
            f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.input(SHIFT);
            f.player.xRot = -148.71614F;
            f.player.yRot = 44.77501F;
            assertExact(f.tick(recorded(205.6128482446265, 62.92159999847412, 230.7578197490457), false));
            f.input(LEFT_SHIFT);
            f.player.xRot = -154.9037F;
            f.player.yRot = 46.01252F;
            assertExact(f.tick(recorded(205.63452620806672, 62.76636799395752, 230.75532561740832), false));

            JavaPistonSlimeLaunchTest.blockEvent(f, base, 1, Direction.WEST.get3DDataValue(), extended);
            f.player.xRot = -159.17873F;
            f.player.yRot = 46.800022F;
            assertExact(f.tick(recorded(205.64875727657815, 62.625, 230.75323522985636), true));

            f.input(LEFT);
            f.player.xRot = -160.6975F;
            f.player.yRot = 47.137527F;
            assertExact(f.tick(recorded(206.06740083569633, 63.0, 230.75129151658413), false));
            f.player.xRot = -160.75374F;
            f.player.yRot = 47.19378F;
            assertExact(f.tick(recorded(206.05563122046914, 63.0, 230.74483021100835), true));
            assertExact(f.tick(recorded(205.9523990415723, 63.0, 230.70664389368253), true));
        }
    }

    /**
     * A 1.21.4 client crawls under a trapdoor beside an up piston that extends. The first pass lifts it 0.4 to the
     * trapdoor, and the second pass's shove is all clipped, so Entity#move's vertical collision leaves no Y delta
     * movement. Once the head stops, the player falls through the head it overlaps from rest: -0.0784, then -0.1552.
     */
    @Test
    void aCrawlerLiftedByAnUpPistonFallsFromRest() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            BlockPos base = new BlockPos(-3, FLOOR_Y - 1, 0);
            int upPiston = piston("up");
            f.player.compensatedWorld.updateBlock(base, upPiston);
            int trapdoor = REGISTRY.block("minecraft:spruce_trapdoor").defaultState();
            f.player.compensatedWorld.updateBlock(new BlockPos(-4, FLOOR_Y + 1, 0), trapdoor);
            f.player.compensatedWorld.updateBlock(new BlockPos(-3, FLOOR_Y + 1, 0), trapdoor);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 crawling = recorded(201.94999998807907, 62.0, 230.69754209922607);
            f.player.xRot = -155.74643F;
            f.player.yRot = 15.862344F;
            f.place(crawling, new Vec3(0, -0.0784000015258789, 0));
            f.input(NONE);
            assertExact(f.tick(crawling, true));

            JavaPistonSlimeLaunchTest.blockEvent(f, base, 0, Direction.UP.get3DDataValue(), upPiston);
            f.player.xRot = -155.85892F;
            f.player.yRot = 16.199844F;
            assertExact(f.tick(crawling, true));
            serverBlock(f, base, REGISTRY.with(upPiston, "extended", "true"));

            f.input(RIGHT);
            f.player.xRot = -159.79645F;
            f.player.yRot = 17.774857F;
            assertExact(f.tick(recorded(201.94999998807907, 62.39999997615814, 230.69957282430968), false));
            f.input(FORWARD_RIGHT);
            f.player.xRot = -166.0965F;
            f.player.yRot = 20.306128F;
            assertExact(f.tick(recorded(201.94999998807907, 62.39999997615814, 230.69527863196905), false));
            serverBlock(
                    f,
                    base.above(),
                    REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", "up"));
            f.player.xRot = -170.70901F;
            f.player.yRot = 21.993643F;
            assertExact(f.tick(recorded(201.94999998807907, 62.321599974632264, 230.68651777923094), false));
            f.input(NONE);
            f.player.xRot = -174.5903F;
            f.player.yRot = 22.781147F;
            assertExact(f.tick(recorded(201.94999998807907, 62.166367970115665, 230.6785454030095), false));
        }
    }

    /**
     * A 26.3 client on a row of shulker boxes, in front of a west piston that fires. The head's two
     * PistonMovingBlockEntity#moveCollidedEntities passes shove it west, one in each packet, onto the next box. That
     * box opens on the packet after the last pass, and ShulkerBoxBlockEntity#moveCollidedEntities lifts the player
     * with the lid.
     */
    @Test
    void aShulkerBoxOpeningAfterAPistonShoveLiftsThePlayer() throws Exception {
        try (var f = new JavaTickFixture()) {
            int shulkerBox = REGISTRY.block("minecraft:shulker_box").defaultState();
            BlockPos box = new BlockPos(-1, FLOOR_Y, 0);
            f.player.compensatedWorld.updateBlock(box, shulkerBox);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), shulkerBox);
            BlockPos base = new BlockPos(1, FLOOR_Y + 1, 0);
            int westPiston = piston("west");
            f.player.compensatedWorld.updateBlock(base, westPiston);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 position = new Vec3(0.5, FLOOR_Y + 1, 0.5);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            f.player.onGround = true;
            f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.input(NOKEYS);
            assertExact(f.tick(position, true));

            JavaPistonSlimeLaunchTest.blockEvent(f, base, 0, Direction.WEST.get3DDataValue(), westPiston);
            for (double face = 1.0; face > 0.0; face -= 0.5) {
                double shove = Math.min(position.x + PLAYER_HALF_WIDTH - (face - 0.5), 0.5) + 0.01;
                position = position.add(new Vec3(-shove, 0, 0));
                assertExact(f.tick(position, false));
            }

            JavaPistonSlimeLaunchTest.blockEvent(f, box, 1, 1, shulkerBox);
            position = new Vec3(position.x, lidShove(FLOOR_Y + 1.0, 0.0F, 0.1F), position.z);
            PredictionResult lifted = f.tick(position, false);
            assertExact(lifted);
            assertNotNull(
                    lifted.getSimulationContext()
                            .getWorldData()
                            .getPistonPushes()
                            .getShulkerBoxShovesAfterTravel(),
                    describe(lifted));
            position = new Vec3(position.x, lidShove(FLOOR_Y + 1.0 + (double) (0.5F * 0.1F), 0.1F, 0.2F), position.z);
            assertExact(f.tick(position, false));
        }
    }

    /**
     * The feet on the lid at {@code y} after ShulkerBoxBlockEntity#moveCollidedEntities from {@code progressOld} to
     * {@code progress}: up by the height of Shulker#getProgressDeltaAabb plus 0.01.
     */
    private static double lidShove(double y, float progressOld, float progress) {
        return y + (FLOOR_Y + 1.0 + (double) progress) - (FLOOR_Y + 1.0 + (double) progressOld) + 0.01;
    }

    private static void serverBlock(JavaTickFixture f, BlockPos pos, int state) {
        f.player.sendTransaction();
        int transaction = f.player.lastTransactionSent.get();
        f.player.compensatedWorld.queueServerBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), state, transaction);
        f.pongThrough(transaction);
    }

    private static int piston(String facing) {
        return REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", facing);
    }

    /** A position from the issue's capture, moved so the capture's floor top 62.0 is FLOOR_Y. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 205.0, y - 62.0 + FLOOR_Y, z - 230.0);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
