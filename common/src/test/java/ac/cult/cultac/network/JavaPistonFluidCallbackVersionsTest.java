package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** LivingEntity's virtual fall callback runs once per reached Entity#move, before collision response. */
class JavaPistonFluidCallbackVersionsTest {
    private static final BlockPos FLUID = new BlockPos(8, 72, 8);
    private static final Vec3 START = new Vec3(7.6, 72, 8.5);
    private static final Vec3 VELOCITY = new Vec3(.1, -.25, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest(name = "{0}: a real piston swept face enters the same water callback")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonMoveIntoWaterUsesTheSameCallback(String release, ClientVersion version) throws Exception {
        try (var f = flowing(version, false)) {
            var piston = new ClientBlockEntityPass.Piston(
                    new BlockPos(7, 72, 8), BlockIds.STONE.defaultState(), Direction.EAST, true, false, 0);
            var after = run(f, travel(f), List.of(piston));
            assertEquals(7.5 + .6F / 2.0D + .01D, after.position().x, 1e-13);
            assertEquals(version == ClientVersion.V_1_21_4 ? 0 : .014D, after.velocity().z, 1e-13);
            assertEquals(version == ClientVersion.V_1_21_4 ? 1 : 0, after.fallDistance(), 0);
        }
    }

    @ParameterizedTest(name = "{0}: entering water adds current in the move's virtual callback")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aBlockEntityMoveIntoWaterAddsTheCurrentToDeltaMovement(String release, ClientVersion version)
            throws Exception {
        try (var f = flowing(version, false)) {
            var after = run(f, travel(f), List.of(move(new Vec3(.5, 0, 0))));
            assertEquals(8.1, after.position().x, 1e-13);
            assertEquals(version == ClientVersion.V_1_21_4 ? 0 : .014D, after.velocity().z, 1e-13);
            assertEquals(version == ClientVersion.V_1_21_4 ? 1 : 0, after.fallDistance(), 0);
        }
    }

    @ParameterizedTest(name = "{0}: later movers retain the water flag after leaving the block")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void multipleMoversShareTheWaterFlagAndDoNotApplyTheCurrentAgain(String release, ClientVersion version)
            throws Exception {
        try (var f = flowing(version, false)) {
            var after = run(
                    f,
                    travel(f),
                    List.of(
                            move(new Vec3(.5, 0, 0)),
                            move(new Vec3(.5, 0, 0)),
                            move(new Vec3(.5, 0, 0)),
                            move(new Vec3(.5, 0, 0)),
                            move(new Vec3(0, -.25, 0))));
            assertEquals(new Vec3(9.6, 71.75, 8.5), after.position());
            assertFalse(JavaFallDistance.touchesWater(
                    f.player,
                    GetBoundingBox.getCollisionBoxForPlayer(
                            f.player, after.position().x, after.position().y, after.position().z)));
            assertEquals(version == ClientVersion.V_1_21_4 ? 0 : .014D, after.velocity().z, 1e-13);
            double expectedFall =
                    version == ClientVersion.V_1_21_4 ? 1 : version.isOlderThan(ClientVersion.V_1_21_5) ? .25D : 0;
            assertEquals(expectedFall, after.fallDistance(), 0);
        }
    }

    @ParameterizedTest(name = "{0}: only the 26.1+ callback refresh also pushes lava")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void enteringLavaRefreshesItsCurrentOnlyInTheCombinedFluidUpdate(String release, ClientVersion version)
            throws Exception {
        try (var f = flowing(version, true)) {
            var after = run(f, travel(f), List.of(move(new Vec3(.5, 0, 0))));
            assertEquals(
                    version.isNewerThanOrEquals(ClientVersion.V_26_1) ? .0023333333333333335D : 0,
                    after.velocity().z,
                    1e-13);
            assertEquals(1, after.fallDistance(), 0);
        }
    }

    @ParameterizedTest(name = "{0}: flying players refresh water contact without a fluid push")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aFlyingPlayerIsNotPushedByFluid(String release, ClientVersion version) throws Exception {
        try (var f = flowing(version, false)) {
            var travel = travel(f);
            f.player.isFlying = true;
            var after = run(f, travel, List.of(move(new Vec3(.5, 0, 0))));
            assertEquals(0, after.velocity().z, 0);
            assertEquals(version == ClientVersion.V_1_21_4 ? 1 : 0, after.fallDistance(), 0);
        }
    }

    private static ClientBlockEntityPass.ShulkerBox move(Vec3 delta) {
        return new ClientBlockEntityPass.ShulkerBox(new SimpleCollisionBox(6, 70, 7, 11, 76, 11), delta);
    }

    private static JavaPassState run(
            JavaTickFixture f, PredictionResult travel, List<ClientBlockEntityPass.Mover> movers) {
        return JavaBlockEntityPass.run(
                        f.player,
                        travel.getSimulationContext(),
                        new ClientBlockEntityPass(movers, 100),
                        new JavaPassState(START, VELOCITY, false, null, null, 1, ClientPistonDeltas.NONE),
                        f.player.pose,
                        false,
                        START)
                .state();
    }

    private static PredictionResult travel(JavaTickFixture f) {
        var result = f.tick(START, false);
        assertTrue(exact(result), describe(result));
        return result;
    }

    private static JavaTickFixture flowing(ClientVersion version, boolean lava) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        int fluid = lava ? BlockIds.LAVA.defaultState() : BlockIds.WATER.defaultState();
        f.player.compensatedWorld.updateBlock(FLUID, fluid);
        f.player.compensatedWorld.updateBlock(FLUID.south().below(), fluid);
        f.place(START, Vec3.ZERO);
        f.input(NONE);
        return f;
    }
}
