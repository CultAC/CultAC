package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsH;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.api.Test;

class ClientAutoJumpTest {
    @Test
    void onlyTheQueuedVanillaJumpSurvivesAReleasedInputTick() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_2)) {
            prepare(f);
            stone(f, 0, 64, 1);
            var engine = f.player.checkManager.getListener(JavaAutoJump.class);
            var complete = complete();
            engine.onPredictionComplete(complete);
            assertFalse(engine.jumpedThisTick());
            f.player.packetStateData.knownInput = new KnownInput(false, false, false, false, true, false, false);
            engine.onPredictionComplete(complete);
            assertTrue(engine.jumpedThisTick());
            assertFalse(ac.cult.cultac.checks.impl.multiactions.MultiActionsC.isVerboseInput(f.player));
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            var check = spy(new MultiActionsH(f.player));
            doReturn(true).when(check).flag();
            check.onPlayerTickEnd(null);
            verify(check, never()).flag();
            f.player.packetStateData.knownInput = KnownInput.DEFAULT;
            engine.onPredictionComplete(complete);
            assertFalse(engine.jumpedThisTick());
        }
    }

    @Test
    void flatGroundTallObstaclesAndLowCeilingsCannotScheduleAnAutomaticJump() {
        for (int layout = 0; layout < 3; layout++) {
            try (var f = new RecordReceiveFixture(ProtocolVersion.V26_2)) {
                prepare(f);
                if (layout != 0) stone(f, 0, 64, 1);
                if (layout == 1) stone(f, 0, 65, 1);
                if (layout == 2) stone(f, 0, 65, 0);
                var engine = f.player.checkManager.getListener(JavaAutoJump.class);
                engine.onPredictionComplete(complete());
                f.player.packetStateData.knownInput = KnownInput.DEFAULT;
                engine.onPredictionComplete(complete());
                assertFalse(engine.jumpedThisTick(), "layout " + layout);
            }
        }
    }

    private static void prepare(RecordReceiveFixture f) {
        var player = f.player;
        player.x = 0.5;
        player.y = 64;
        player.z = 0.598;
        player.boundingBox = GetBoundingBox.getPlayerBoundingBox(player, player.x, player.y, player.z);
        player.onGround = true;
        player.compensatedEntities.getSelf().playerSpeed = 0.1;
        player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
        player.packetStateData.knownInput = new KnownInput(true, false, false, false, false, false, false);
    }

    private static void stone(RecordReceiveFixture f, int x, int y, int z) {
        f.player.compensatedWorld.updateBlock(
                new BlockPos(x, y, z),
                DataTables.defaults().registry().block("minecraft:stone").defaultState());
    }

    private static PredictionComplete complete() {
        var result = mock(PredictionResult.class);
        var context = mock(SimulationContext.class, RETURNS_DEEP_STUBS);
        when(result.getTarget()).thenReturn(new Vec3(0, 0, 0.098));
        when(result.getSimulationContext()).thenReturn(context);
        when(context.getLastStuckSpeed()).thenReturn(new Vec3(1, 1, 1));
        when(result.getCollideAxisData())
                .thenReturn(new ac.cult.cultac.utils.data.CollideAxisData(
                        null,
                        null,
                        new ac.cult.cultac.utils.data.CollideAxisData.CollideResult(true, 0),
                        null,
                        java.util.List.of()));
        return new PredictionComplete(result);
    }
}
