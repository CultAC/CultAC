package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JavaRemotePistonCallbacksVersionsTest {
    private static final Vec3 VELOCITY = new Vec3(0.2, -0.05, 0.1);

    static Stream<Arguments> shulkerPistons() {
        return JavaPistonReleaseCases.releases()
                .flatMap(release -> Stream.of(
                        Arguments.of(release.get()[0], release.get()[1], "stone"),
                        Arguments.of(release.get()[0], release.get()[1], "slime_block")));
    }

    /** Shulker#setPos snaps a piston move to the grid; get/setDeltaMovement keep every callback's velocity zero. */
    @ParameterizedTest(name = "{0} moved={2}")
    @MethodSource("shulkerPistons")
    void aPistonUsesTheShulkersPositionAndVelocityOverrides(String release, ClientVersion version, String moved)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.SHULKER, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), block("bubble_column"));
            var piston = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y, 0), block(moved), Direction.EAST, true, false, 0);
            var after = JavaBlockEntityPass.runEntity(
                            f.player, actor, new ClientBlockEntityPass(List.of(piston), 10), state(start, 0))
                    .state();
            assertEquals(new Vec3(1.5, FLOOR_Y, 0.5), after.position());
            assertEquals(after.position(), actor.clientPhysicalPosition);
            assertEquals(Vec3.ZERO, after.velocity());
        }
    }

    /** Shulker#move(SHULKER_BOX) only tries teleportSomewhere, which is false with receipt-proven NoAI. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anOpeningBoxCannotTranslateANoAiShulker(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.SHULKER, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            actor.noAI = true;
            var box = new ClientBlockEntityPass.ShulkerBox(
                    new SimpleCollisionBox(0, FLOOR_Y, 0, 1, FLOOR_Y + 1, 1), new Vec3(0.2, 0, 0));
            var after = JavaBlockEntityPass.runEntity(
                            f.player, actor, new ClientBlockEntityPass(List.of(box), 10), state(start, 0))
                    .state();
            assertEquals(start, after.position());
            assertEquals(Vec3.ZERO, after.velocity());
            assertTrue(after.onGround());
        }
    }

    /** The ordinary minecart's move invokes its own stationary block pass before the piston's explicit pass. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonKeepsTheMinecartsExtraBlockCallback(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.4, FLOOR_Y + 0.1, 0.5);
            f.player.compensatedEntities.addEntity(100, type("minecart"), start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            int up = DataTables.defaults().registry().with(block("bubble_column"), "drag", "false");
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), up);
            var piston = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y, 0), block("stone"), Direction.EAST, true, false, 0);
            var after = JavaBlockEntityPass.runEntity(
                            f.player, actor, new ClientBlockEntityPass(List.of(piston), 10), state(start, 0))
                    .state();
            assertTrue(after.position().x > start.x);
            assertEquals(
                    version.isOlderThan(ClientVersion.V_26_3) ? VELOCITY.y + 0.1 + 0.1 : VELOCITY.y,
                    after.velocity().y,
                    1.0E-12);
        }
    }

    static Stream<Arguments> stuckOverrides() {
        return JavaPistonReleaseCases.releases()
                .flatMap(release -> Stream.of(
                        Arguments.of(release.get()[0], release.get()[1], EntityTypeIds.SPIDER, "cobweb"),
                        Arguments.of(release.get()[0], release.get()[1], type("cave_spider"), "cobweb"),
                        Arguments.of(release.get()[0], release.get()[1], type("wither"), "cobweb"),
                        Arguments.of(release.get()[0], release.get()[1], type("wither"), "sweet_berry_bush"),
                        Arguments.of(release.get()[0], release.get()[1], type("wither"), "powder_snow")));
    }

    /** Spider ignores only cobweb; WitherBoss ignores makeStuckInBlock altogether, including its fall reset. */
    @ParameterizedTest(name = "{0} entity={2} block={3}")
    @MethodSource("stuckOverrides")
    void aPistonUsesTheActorsStuckCallback(String release, ClientVersion version, int type, String block)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.4, FLOOR_Y + 0.1, 0.5);
            f.player.compensatedEntities.addEntity(100, type, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), block(block));
            var before = state(start, 7);
            var piston = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y, 0), block("stone"), Direction.EAST, true, false, 0);
            var after = JavaBlockEntityPass.runEntity(
                            f.player, actor, new ClientBlockEntityPass(List.of(piston), 10), before)
                    .state();
            assertTrue(
                    after.position().x > start.x, "the piston must actually move the actor through the callback block");
            assertNull(after.stuckSpeed());
            assertEquals(7, after.fallDistance());
            assertEquals(VELOCITY, after.velocity());
        }
    }

    /** Player#getBlockSpeedFactor bypasses honey while the shared fall-flying flag is set. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonReadsTheRemotePlayersGlidingFlag(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.4, FLOOR_Y + 0.1, 0.5);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.PLAYER, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            actor.actionSharedFlags = (byte) 128;
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), block("honey_block"));
            var piston = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y + 1, 0), block("stone"), Direction.EAST, true, false, 0);
            var after = JavaBlockEntityPass.runEntity(
                            f.player, actor, new ClientBlockEntityPass(List.of(piston), 10), state(start, 0))
                    .state();
            assertTrue(after.position().x > start.x);
            assertEquals(VELOCITY, after.velocity());
        }
    }

    /** AbstractMinecart#getBlockSpeedFactor reads rails at the center before Entity's supporting-block fallback. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonDoesNotApplyHoneyThroughAMinecartsRail(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.4, FLOOR_Y + 0.1, 0.5);
            f.player.compensatedEntities.addEntity(100, type("minecart"), start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), block("rail"));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), block("honey_block"));
            var piston = new ClientBlockEntityPass.Piston(
                    new BlockPos(-1, FLOOR_Y, 0), block("stone"), Direction.EAST, true, false, 0.5F);
            var after = JavaBlockEntityPass.runEntity(
                            f.player, actor, new ClientBlockEntityPass(List.of(piston), 10), state(start, 0))
                    .state();
            assertTrue(after.position().x > start.x && after.position().x < 1);
            assertEquals(VELOCITY, after.velocity());
        }
    }

    private static JavaPassState state(Vec3 start, double fall) {
        return new JavaPassState(
                start, VELOCITY, true, new MainSupportingBlockData(null, false), null, fall, ClientPistonDeltas.NONE);
    }

    private static int block(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }

    private static int type(String name) {
        return EntityTypes.defaults().byKey("minecraft:" + name).id();
    }
}
