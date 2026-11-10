package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Player#tick consumes the cached eye fluid before Entity#baseTick refreshes it. */
class JavaFluidEyeCarryVersionsTest {
    private static final Vec3 START = new Vec3(7.6, 72, 8.5);
    private static final Vec3 END = START.add(.5, 0, 0);
    private static final double FALL = .0784000015258789D;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest(name = "{0}: travel into deep water leaves the callback's eye cache")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void dryTravelIntoDeepWaterRefreshesTheModernEyeCache(String release, ClientVersion version) throws Exception {
        try (var f = deepWater(version, START)) {
            var moved = f.tick(END, false);
            assertTrue(exact(moved), describe(moved));
            assertFalse(moved.getSimulationContext().getWorldData().isEyeInWater());
            assertEquals(
                    modern(version),
                    f.player.checkManager.getSimulationProcessor().isLastWorldEyeInWater());
        }
    }

    @ParameterizedTest(name = "{0}: next tick's underwater state reads the prior travel callback")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void theNextPlayerTickUsesTheCachedContactBeforeItsBaseTick(String release, ClientVersion version)
            throws Exception {
        try (var f = deepWater(version, START)) {
            var moved = f.tick(END, false);
            assertTrue(exact(moved), describe(moved));
            // Dry air travel stores horizontal drag and ordinary gravity before the next water travel.
            var next = f.tick(END.add(.5 * (double) .91F, -FALL, 0), false);
            assertTrue(exact(next), describe(next));
            assertTrue(next.getSimulationContext().getWorldData().isEyeInWater());
            assertEquals(
                    modern(version), next.getSimulationContext().getWorldData().isUnderwater());
        }
    }

    @ParameterizedTest(name = "{0}: already-wet travel retains a dry eye despite a deep-water endpoint")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anAlreadyWetBodyDoesNotRefreshItsDryEyeDuringMove(String release, ClientVersion version) throws Exception {
        Vec3 start = new Vec3(7.8, 72, 8.5);
        try (var f = deepWater(version, start)) {
            var moved = f.tick(start.add(.5, 0, 0), false);
            assertTrue(exact(moved), describe(moved));
            assertEquals(true, moved.getSimulationContext().getWorldData().isInWater());
            assertFalse(moved.getSimulationContext().getWorldData().isEyeInWater());
            assertFalse(f.player.checkManager.getSimulationProcessor().isLastWorldEyeInWater());
        }
    }

    @ParameterizedTest(name = "{0}: an upward piston actually pushes the standing eye into water")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anUpwardPistonRefreshesTheModernCacheAfterTheEarlierTravel(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var blocks = DataTables.defaults().registry();
            var base = new BlockPos(8, 71, 8);
            int piston = blocks.with(BlockIds.PISTON.defaultState(), "facing", "up");
            f.player.compensatedWorld.updateBlock(base, piston);
            f.player.compensatedWorld.updateBlock(new BlockPos(8, 74, 8), BlockIds.WATER.defaultState());
            settle(f);
            Vec3 start = new Vec3(8.5, 72, 8.5);
            f.place(start, Vec3.ZERO);
            f.input(NONE);
            f.player.compensatedWorld.pistons.handleBlockEvent(base, blocks.blockIndex(piston), 0, 1, 0);
            Vec3 report = version.isNewerThanOrEquals(ClientVersion.V_26_3) ? start.add(0, .51, 0) : start;
            var moved = f.tick(report, false);
            assertTrue(exact(moved), describe(moved));
            assertFalse(moved.getSimulationContext().getWorldData().isEyeInWater());
            assertEquals(72.51, f.player.y, 1e-12, "the actual piston pass moved the player upward");
            assertEquals(
                    modern(version),
                    f.player.checkManager.getSimulationProcessor().isLastWorldEyeInWater());
        }
    }

    @ParameterizedTest(name = "{0}: leaving water in a later move retains the earlier refresh")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aLaterMoverLeavingWaterDoesNotReplaceTheCachedEyeWithTheEndpoint(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedWorld.updateBlock(new BlockPos(8, 74, 8), BlockIds.WATER.defaultState());
            settle(f);
            Vec3 start = new Vec3(8.5, 72, 8.5);
            f.place(start, Vec3.ZERO);
            f.input(NONE);
            var before = f.tick(start, false);
            assertTrue(exact(before), describe(before));
            var pass = new ClientBlockEntityPass(List.of(move(new Vec3(0, .5, 0)), move(new Vec3(2, 0, 0))), 100);
            var after = JavaBlockEntityPass.run(
                            f.player, before.getSimulationContext(), pass, state(start), Pose.STANDING, false, start)
                    .state();
            assertEquals(start.add(2, .5, 0), after.position());
            var body = GetBoundingBox.getCollisionBoxForPlayer(
                    f.player, after.position().x, after.position().y, after.position().z);
            assertFalse(ClientFluidQueries.eyesInWater(
                    f.player, body, after.position(), after.position().y + Pose.STANDING.eyeHeight));
            assertEquals(modern(version), after.playerEyeInWater());
            assertEquals(version != ClientVersion.V_1_21_4, after.inWater());
        }
    }

    @ParameterizedTest(name = "{0}: late {2} pose samples its own eye height")
    @MethodSource("poseCases")
    void aLateMoveUsesThePassPoseRatherThanTheEarlierTravelPose(String release, ClientVersion version, Pose pose)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedWorld.updateBlock(new BlockPos(8, 72, 8), BlockIds.WATER.defaultState());
            settle(f);
            f.place(START, Vec3.ZERO);
            f.input(NONE);
            var before = f.tick(START, false);
            assertTrue(exact(before), describe(before));
            assertEquals(Pose.STANDING, before.getSimulationContext().getPose());
            var pass = new ClientBlockEntityPass(List.of(move(new Vec3(.5, 0, 0))), 100);
            var after = JavaBlockEntityPass.run(
                            f.player, before.getSimulationContext(), pass, state(START), pose, false, START)
                    .state();
            assertEquals(END, after.position());
            assertEquals(modern(version) && pose == Pose.FALL_FLYING, after.playerEyeInWater());
        }
    }

    @ParameterizedTest(name = "{0}: cached eye alternatives keep their own next-tick worlds")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void worldReuseDoesNotMixCarriedEyeContactBetweenCandidates(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 at = new Vec3(.5, 64, .5);
            Vec3 velocity = new Vec3(0, -FALL, 0);
            f.place(at, velocity);
            f.input(NONE);
            var warm = f.tick(at, true);
            assertTrue(exact(warm), describe(warm));
            var states = List.of(
                    new JavaPredictionCarry.State(velocity, 0, null, null, null, false, false),
                    new JavaPredictionCarry.State(velocity, 0, null, null, null, false, true));
            JavaTickFixture.setField(
                    SimulationProcessor.class,
                    f.player.checkManager.getSimulationProcessor(),
                    "profileCarry",
                    new JavaPredictionCarry(f.player.compensatedEntities.getSelf(), 0, states));
            var next = f.tick(at, true);
            assertTrue(exact(next), describe(next));
            Set<Boolean> eyes = next.getRealities().stream()
                    .map(r -> r.getSimulationContext().getWorldData().isUnderwater())
                    .collect(Collectors.toSet());
            assertEquals(modern(version) ? Set.of(false, true) : Set.of(false), eyes);
        }
    }

    @ParameterizedTest(name = "{0}: a respawn creates a fresh empty player eye cache")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aNewLocalPlayerDoesNotInheritThePriorPlayersCachedEyeFluid(String release, ClientVersion version)
            throws Exception {
        try (var f = deepWater(version, START)) {
            var moved = f.tick(END, false);
            assertTrue(exact(moved), describe(moved));
            var wet = f.tick(END.add(.5 * (double) .91F, -FALL, 0), false);
            assertTrue(exact(wet), describe(wet));
            assertTrue(f.player.checkManager.getSimulationProcessor().isLastWorldEyeInWater());
            // This is the engine hook the transaction-ordered respawn handler calls for every new LocalPlayer.
            f.player.checkManager.getSimulationProcessor().handleRespawn();
            assertFalse(f.player.checkManager.getSimulationProcessor().isLastWorldEyeInWater());
            f.place(START, Vec3.ZERO);
            var fresh = f.tick(START, false);
            assertTrue(exact(fresh), describe(fresh));
            assertFalse(fresh.getSimulationContext().getWorldData().isUnderwater());
            assertFalse(fresh.getSimulationContext().getWorldData().isEyeInWater());
        }
    }

    private static Stream<Arguments> poseCases() {
        return JavaPistonReleaseCases.releases()
                .flatMap(args -> Stream.of(Pose.STANDING, Pose.FALL_FLYING)
                        .map(pose -> Arguments.of(args.get()[0], args.get()[1], pose)));
    }

    private static JavaPassState state(Vec3 position) {
        return new JavaPassState(position, Vec3.ZERO, false, null, null, 0, ClientPistonDeltas.NONE);
    }

    private static ClientBlockEntityPass.ShulkerBox move(Vec3 delta) {
        return new ClientBlockEntityPass.ShulkerBox(new SimpleCollisionBox(6, 70, 7, 12, 78, 10), delta);
    }

    private static JavaTickFixture deepWater(ClientVersion version, Vec3 start) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.compensatedWorld.updateBlock(new BlockPos(8, 72, 8), BlockIds.WATER.defaultState());
        f.player.compensatedWorld.updateBlock(new BlockPos(8, 73, 8), BlockIds.WATER.defaultState());
        settle(f);
        f.place(start, new Vec3(.5, 0, 0));
        f.input(NONE);
        return f;
    }

    private static void settle(JavaTickFixture f) {
        for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static boolean modern(ClientVersion version) {
        return version.isNewerThanOrEquals(ClientVersion.V_26_1);
    }
}
