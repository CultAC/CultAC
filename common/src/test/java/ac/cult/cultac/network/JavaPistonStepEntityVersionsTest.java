package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaPistonStepEntityVersionsTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();

    /** Only 26.3 Entity#collide expands the initial entity query upward by maxUpStep. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonStepUsesTheVersionsOwnEntityQueryHeight(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 1.8);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, start, 0, 0, 0);
            f.player.compensatedEntities.addEntity(
                    101, EntityTypeIds.OAK_BOAT, new Vec3(0.5, FLOOR_Y + 2.0, 1.3), 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 0),
                    BLOCKS.block("minecraft:stone_slab").defaultState());
            var state = new JavaPassState(
                    start, Vec3.ZERO, true, new MainSupportingBlockData(null, true), null, 0, ClientPistonDeltas.NONE);
            var piston = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y + 1, 1),
                    BLOCKS.with(BLOCKS.block("minecraft:piston_head").defaultState(), "facing", "north"),
                    Direction.NORTH,
                    true,
                    true,
                    0);
            var after = JavaBlockEntityPass.runEntity(
                            f.player, actor, new ClientBlockEntityPass(List.of(piston), 10), state)
                    .state();
            assertEquals(
                    version.isOlderThan(ClientVersion.V_26_3) ? FLOOR_Y + 0.5 : FLOOR_Y, after.position().y, 1.0E-12);
        }
    }
}
