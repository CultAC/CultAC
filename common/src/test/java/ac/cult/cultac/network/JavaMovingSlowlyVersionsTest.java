package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** LocalPlayer#isMovingSlowly reads crouching or a dry visual crawl, independently of pose size. */
class JavaMovingSlowlyVersionsTest {
    private static final Vec3 START = new Vec3(.5, 64, .5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("cases")
    void inputUsesTheActualVisualCrawlAndCrouchPredicates(String release, ClientVersion version, Scenario scenario)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(START, Vec3.ZERO);
            f.input(NONE);
            var warm = f.tick(START, true);
            assertTrue(exact(warm), describe(warm));
            var context = warm.getSimulationContext();
            if (scenario.lowCeiling) {
                var blocks = DataTables.defaults().registry();
                int topSlab = blocks.with(blocks.block("minecraft:stone_slab").defaultState(), "type", "top");
                f.player.compensatedWorld.updateBlock(new BlockPos(0, 65, 0), topSlab);
                for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            }
            // An exact input-time frame: the pose left last tick, the completed baseTick fluid/swim
            // state, abilities and the preceding keys that aiStep reads before Input.tick.
            JavaTickFixture.setField(SimulationContext.class, context, "pose", scenario.pose);
            JavaTickFixture.setField(SimulationContext.class, context, "gliding", scenario.gliding);
            JavaTickFixture.setField(SimulationContext.class, context, "lastSneaking", scenario.previousShift);
            context.getWorldData().setInWater(scenario.water);
            JavaTickFixture.setField(WorldData.class, context.getWorldData(), "swimming", scenario.swimming);
            f.player.swimState.onServerFlag(scenario.swimming);
            f.player.isSprinting = scenario.swimming;
            JavaTickFixture.setField(
                    SimulationContext.class, context, "isSprinting", DesyncStatus.fromBoolean(scenario.swimming));
            f.player.pose = scenario.pose;
            f.player.isGliding = scenario.gliding;
            f.player.isFlying = scenario.flying;
            assertTrue(f.player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, START.x, START.y, START.z));
            assertEquals(
                    !scenario.lowCeiling,
                    f.player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, START.x, START.y, START.z));
            float expected = scenario.slow
                    ? (float)
                            f.player.compensatedEntities.getSelf().sneakingSpeed.value()
                    : 1.0F;
            assertArrayEquals(new float[] {expected}, ClientKeyInput.movingSlowlyFactors(f.player, context), 0.0F);
        }
    }

    @ParameterizedTest(name = "{0}: exact water contact selects the crawl outcome")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void visualCrawlingUsesExactWaterContact(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(START, Vec3.ZERO);
            f.input(NONE);
            var warm = f.tick(START, true);
            assertTrue(exact(warm), describe(warm));
            var context = warm.getSimulationContext();
            JavaTickFixture.setField(SimulationContext.class, context, "pose", Pose.SWIMMING);
            float slow =
                    (float) f.player.compensatedEntities.getSelf().sneakingSpeed.value();
            context.getWorldData().setInWater(true);
            assertArrayEquals(new float[] {1.0F}, ClientKeyInput.movingSlowlyFactors(f.player, context), 0.0F);
            context.getWorldData().setInWater(false);
            assertArrayEquals(new float[] {slow}, ClientKeyInput.movingSlowlyFactors(f.player, context), 0.0F);
        }
    }

    @ParameterizedTest(name = "{0}: a passenger's shift does not crouch")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPassengerCannotCrouchWithShift(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(START, Vec3.ZERO);
            f.input(NONE);
            var warm = f.tick(START, true);
            assertTrue(exact(warm), describe(warm));
            var entities = f.player.compensatedEntities;
            entities.addEntity(91, EntityTypeIds.PIG, START, 0, 0, 0);
            var vehicle = entities.getEntity(91);
            entities.getSelf().mount(vehicle);
            assertTrue(entities.getSelf().inVehicle());
            var context = warm.getSimulationContext();
            JavaTickFixture.setField(SimulationContext.class, context, "vehicle", vehicle);
            JavaTickFixture.setField(SimulationContext.class, context, "lastSneaking", true);
            assertArrayEquals(new float[] {1.0F}, ClientKeyInput.movingSlowlyFactors(f.player, context), 0.0F);
        }
    }

    private static Stream<Arguments> cases() {
        return JavaPistonReleaseCases.releases()
                .flatMap(release -> Stream.of(
                                new Scenario(
                                        "ordinary walking",
                                        Pose.STANDING,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false),
                                new Scenario(
                                        "genuine shift crouch",
                                        Pose.STANDING,
                                        false,
                                        false,
                                        false,
                                        true,
                                        false,
                                        false,
                                        true),
                                new Scenario(
                                        "forced low ceiling",
                                        Pose.STANDING,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        true,
                                        true),
                                new Scenario(
                                        "dry swimming pose crawls",
                                        Pose.SWIMMING,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        true),
                                new Scenario(
                                        "wet swimming pose keeps full input",
                                        Pose.SWIMMING,
                                        true,
                                        true,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false),
                                new Scenario(
                                        "swimming blocks shift crouch",
                                        Pose.SWIMMING,
                                        true,
                                        true,
                                        false,
                                        true,
                                        false,
                                        false,
                                        false),
                                new Scenario(
                                        "wet stale swimming pose",
                                        Pose.SWIMMING,
                                        true,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false),
                                new Scenario(
                                        "dry inactive fall-flying pose crawls",
                                        Pose.FALL_FLYING,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        true),
                                new Scenario(
                                        "active fall-flying pose is not a crawl",
                                        Pose.FALL_FLYING,
                                        false,
                                        false,
                                        true,
                                        false,
                                        false,
                                        false,
                                        false),
                                new Scenario(
                                        "wet inactive fall-flying pose is not a crawl",
                                        Pose.FALL_FLYING,
                                        true,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false),
                                new Scenario(
                                        "flight blocks shift crouch",
                                        Pose.STANDING,
                                        false,
                                        false,
                                        false,
                                        true,
                                        true,
                                        false,
                                        false),
                                new Scenario(
                                        "wet actual crouch still slows",
                                        Pose.SWIMMING,
                                        true,
                                        false,
                                        false,
                                        true,
                                        false,
                                        false,
                                        true))
                        .map(scenario -> Arguments.of(release.get()[0], release.get()[1], scenario)));
    }

    private record Scenario(
            String name,
            Pose pose,
            boolean water,
            boolean swimming,
            boolean gliding,
            boolean previousShift,
            boolean flying,
            boolean lowCeiling,
            boolean slow) {
        @Override
        public String toString() {
            return name;
        }
    }
}
