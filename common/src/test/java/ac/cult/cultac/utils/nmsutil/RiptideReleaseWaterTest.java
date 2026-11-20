package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** TridentItem#releaseUsing reads Entity#wasTouchingWater, which the last move left, not the live world. */
class RiptideReleaseWaterTest {
    @Test
    void aReleaseAfterAWetMoveIsValidInADrainedWorld() {
        assertTrue(withPlayer(false, player -> canRelease(player, DesyncStatus.TRUE)));
    }

    @Test
    void aReleaseAfterADryMoveIsDryInAFloodedWorld() {
        assertFalse(withPlayer(true, player -> canRelease(player, DesyncStatus.FALSE)));
    }

    @Test
    void aReleaseAfterAMoveOfUnknownWaterStateIsValid() {
        assertTrue(withPlayer(false, player -> canRelease(player, DesyncStatus.UNKNOWN)));
    }

    @Test
    void aReleaseWithoutACommittedMoveProbesTheWorld() {
        assertTrue(withPlayer(true, player -> canRelease(player, null)));
        assertFalse(withPlayer(false, player -> canRelease(player, null)));
    }

    @Test
    void onlyAWetLastMoveLeavesWasTouchingWaterForTheRelease() {
        assertTrue(withPlayer(false, player -> spins(player, DesyncStatus.TRUE)));
        assertFalse(withPlayer(true, player -> spins(player, DesyncStatus.UNKNOWN)));
        assertFalse(withPlayer(true, player -> spins(player, null)));
    }

    private static boolean canRelease(CultPlayer player, DesyncStatus touchingWater) {
        return afterMove(player, touchingWater, RiptideUtil::canUseRiptideAtCurrentState);
    }

    private static boolean spins(CultPlayer player, DesyncStatus touchingWater) {
        return afterMove(player, touchingWater, RiptideUtil::isCertainRiptideRelease);
    }

    private static boolean afterMove(CultPlayer player, DesyncStatus touchingWater, Predicate<CultPlayer> rule) {
        PredictionResult last = mock(PredictionResult.class);
        when(last.getJavaCommittedMove())
                .thenReturn(
                        touchingWater == null
                                ? null
                                : new JavaFallDistance.Moved(0.0D, Vec3.ZERO, false, touchingWater));
        SimulationProcessor processor = mock(SimulationProcessor.class);
        when(processor.getLastPrediction()).thenReturn(last);
        CheckManager checkManager = mock(CheckManager.class);
        when(checkManager.getSimulationProcessor()).thenReturn(processor);
        CheckManager real = player.checkManager;
        player.checkManager = checkManager;
        try {
            return rule.test(player);
        } finally {
            player.checkManager = real;
        }
    }

    private static boolean withPlayer(boolean waterInWorld, Predicate<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Riptide_Water"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            if (waterInWorld) {
                player.compensatedWorld.updateBlock(
                        0,
                        64,
                        0,
                        DataTables.defaults()
                                .registry()
                                .block("minecraft:water")
                                .defaultState());
            }
            player.x = 0.5D;
            player.y = 64.0D;
            player.z = 0.5D;
            return test.test(player);
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) {
                embedded.runPendingTasks();
                embedded.runScheduledPendingTasks();
                embedded.finishAndReleaseAll();
            }
        }
    }
}
