package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaPistonSupportVersionsTest {
    /** Entity#checkSupportingBlock reads the onGroundNoBlocks bit at this candidate's piston-adjusted start. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void supportFallbackUsesTheCompleteStartingState(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            var previous = f.tick(start, true);
            BlockPos oldFloor = new BlockPos(0, FLOOR_Y - 1, 0);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 0), BlockIds.AIR.defaultState());
            var context = previous.getSimulationContext().forTravelReplay(start.add(1, 0, 0));
            JavaTickFixture.setField(
                    context.getClass(),
                    context,
                    "lastTickMainSupportingBlockData",
                    new MainSupportingBlockData(null, true));
            var noFallback = new WorldStageBuilder().generateWorldData(f.player, context, previous, DesyncStatus.TRUE);
            assertNull(noFallback.getMainSupportingBlockPos().getBlockPos());
            JavaTickFixture.setField(
                    context.getClass(),
                    context,
                    "lastTickMainSupportingBlockData",
                    new MainSupportingBlockData(oldFloor, true));
            var fallback = new WorldStageBuilder().generateWorldData(f.player, context, previous, DesyncStatus.TRUE);
            assertEquals(oldFloor, fallback.getMainSupportingBlockPos().getBlockPos());
        }
    }
}
