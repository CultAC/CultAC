package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaLocalPistonCollisionVersionsTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();

    /** PowderSnowBlock#getCollisionShape reads this Entity#move's fallDistance, before the pass's block effects. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void powderSnowReadsThePushedStatesFallDistance(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(0.6, FLOOR_Y + 0.5, 0.5);
            var context = context(f, version, position);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(1, FLOOR_Y, 0),
                    BLOCKS.block("minecraft:powder_snow").defaultState());
            for (double fallDistance : new double[] {0, 3}) {
                JavaTickFixture.setField(
                        SimulationProcessor.class,
                        f.player.checkManager.getSimulationProcessor(),
                        "profileCarry",
                        new JavaPredictionCarry(f.player.compensatedEntities.getSelf(), 3 - fallDistance));
                var after = push(
                        f, context, position, false, fallDistance, Direction.EAST, new BlockPos(0, FLOOR_Y + 1, 0));
                double expected = fallDistance == 0 ? 0.5 + (double) (0.6F / 2) + 0.01 : 1 - (double) (0.6F / 2);
                assertEquals(expected, after.position().x, 1.0E-12, "fall distance " + fallDistance);
            }
        }
    }

    /** Entity#collide includes Level#getEntityCollisions for a boat the piston pushes the local player against. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void theLocalPlayerClipsAgainstABoatDuringTheShove(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(0.7, FLOOR_Y + 0.5, 0.5);
            var context = context(f, version, position);
            f.player.compensatedEntities.addEntity(
                    100, EntityTypeIds.OAK_BOAT, new Vec3(1.75, position.y, 0.5), 0, 0, 0);
            var after = push(f, context, position, false, 0, Direction.EAST, new BlockPos(0, FLOOR_Y + 1, 0));
            assertEquals(1.75 - 1.375F / 2 - (double) (0.6F / 2), after.position().x, 1.0E-12);
        }
    }

    /** The local and remote move collect the same source-defined query; only 26.3 includes the overhead boat. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aLocalPistonStepUsesTheVersionsOwnEntityQuery(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 1.8);
            var context = context(f, version, position);
            f.player.compensatedEntities.addEntity(
                    100, EntityTypeIds.OAK_BOAT, new Vec3(0.5, FLOOR_Y + 2, 1.3), 0, 0, 0);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 0),
                    BLOCKS.block("minecraft:stone_slab").defaultState());
            var after = push(f, context, position, true, 0, Direction.NORTH, new BlockPos(0, FLOOR_Y + 1, 1));
            assertEquals(
                    version.isOlderThan(ClientVersion.V_26_3) ? FLOOR_Y + 0.5 : FLOOR_Y, after.position().y, 1.0E-12);
        }
    }

    private static SimulationContext context(JavaTickFixture f, ClientVersion version, Vec3 position) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(position, Vec3.ZERO);
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        var prediction = f.tick(position, position.y == FLOOR_Y);
        assertTrue(exact(prediction));
        return prediction.getSimulationContext();
    }

    private static JavaPassState push(
            JavaTickFixture f,
            SimulationContext context,
            Vec3 position,
            boolean ground,
            double fallDistance,
            Direction direction,
            BlockPos pos) {
        int head = BLOCKS.with(
                BLOCKS.block("minecraft:piston_head").defaultState(),
                "facing",
                direction.name().toLowerCase(java.util.Locale.ROOT));
        var pass = new ClientBlockEntityPass(
                List.of(new ClientBlockEntityPass.Piston(pos, head, direction, true, true, 0)), 10);
        var state = new JavaPassState(
                position,
                Vec3.ZERO,
                ground,
                new MainSupportingBlockData(null, ground),
                null,
                fallDistance,
                ClientPistonDeltas.NONE);
        return JavaBlockEntityPass.run(f.player, context, pass, state, Pose.STANDING, false, position)
                .state();
    }
}
