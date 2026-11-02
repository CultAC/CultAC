package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementPatch;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaPistonBubbleVersionsTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final Vec3 POSITION = new Vec3(0.5, FLOOR_Y + 5, 0.5);
    private static final Vec3 VELOCITY = new Vec3(0.04, 0.2, 0.02);

    /** BubbleColumnBlock switches from above.isAir to empty collision and fluid in 1.21.5. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonBlockPassUsesTheVersionsOwnBubbleSurfaceTest(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            setup(f, version, "minecraft:oak_button");
            var context = f.tick(POSITION, false).getSimulationContext();
            var scan = JavaInsideBlockEffects.scan(f.player, context, POSITION, POSITION, null);
            var result = JavaInsideBlockEffects.resolveBlockEntityMove(f.player, context, scan, 3, VELOCITY, false);
            boolean surface = version.isNewerThanOrEquals(ClientVersion.V_1_21_5);
            assertEquals(new Vec3(0.04, 0.2 + (surface ? 0.1 : 0.06), 0.02), result.velocity());
            assertEquals(surface ? 3 : 0, result.fallDistance());
        }
    }

    /** From 26.3 the base Entity bubble callback changes velocity only on a movement-simulating actor. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aRemoteMobUsesTheVersionsOwnBubbleAuthorityRule(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            setup(f, version, "minecraft:stone");
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, POSITION, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            var box = GetBoundingBox.getPacketEntityBoundingBox(f.player, POSITION.x, POSITION.y, POSITION.z, actor);
            var scan = JavaInsideBlockEffects.scan(f.player, actor, POSITION, box, box, null);
            var result = JavaInsideBlockEffects.resolveBlockEntityMove(f.player, null, scan, 3, VELOCITY, false);
            assertEquals(
                    version.isOlderThan(ClientVersion.V_26_3) ? new Vec3(0.04, 0.26, 0.02) : VELOCITY,
                    result.velocity());
            assertEquals(0, result.fallDistance(), "the submerged callback still resets fall distance");
        }
    }

    private static void setup(JavaTickFixture f, ClientVersion version, String above) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(POSITION, Vec3.ZERO);
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        f.player.compensatedWorld.updateBlock(
                new BlockPos(0, FLOOR_Y + 5, 0),
                BLOCKS.with(BLOCKS.block("minecraft:bubble_column").defaultState(), "drag", "false"));
        f.player.compensatedWorld.updateBlock(
                new BlockPos(0, FLOOR_Y + 6, 0), BLOCKS.block(above).defaultState());
    }

    /** The small piston exit visits its start block from 1.21.9; only 1.21.9 applies the imprecise bubble callback. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void leavingAColumnUsesTheVersionsOwnPreciseCallbackRule(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            setup(f, version, "minecraft:stone");
            var context = f.tick(POSITION, false).getSimulationContext();
            if (version == ClientVersion.V_1_21_9)
                context.setMovementPatch(
                        release.equals("1.21.10") ? JavaMovementPatch.V_1_21_10 : JavaMovementPatch.V_1_21_9);
            var scan = JavaInsideBlockEffects.scan(
                    f.player, context, new Vec3(0.8, POSITION.y, 0.5), new Vec3(1.31, POSITION.y, 0.5), null);
            var result = JavaInsideBlockEffects.resolveBlockEntityMove(f.player, context, scan, 3, VELOCITY, false);
            assertEquals(release.equals("1.21.9") ? new Vec3(0.04, 0.26, 0.02) : VELOCITY, result.velocity());
            assertEquals(release.equals("1.21.9") ? 0 : 3, result.fallDistance());
        }
    }
}
